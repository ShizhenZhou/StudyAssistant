/* PDF semantic input only. No screen coordinates, screenshot layers or PDF page scaling. */
(function () {
  'use strict';
  var messages = [], fontCss = '', cache = new Map(), characters = 0, pendingAssets = [];
  window.conversationPdfState = {status:'idle'};
  function imageLoaded(src) {
    return new Promise(function (resolve, reject) {
      var image = new Image(); image.onload = function () { resolve(image); }; image.onerror = reject; image.src = src;
    });
  }
  async function raster(src, max) {
    var image = await imageLoaded(src);
    var scale = Math.min(1, max / Math.max(image.naturalWidth, image.naturalHeight));
    var canvas = document.createElement('canvas');
    canvas.width = Math.max(1, Math.ceil(image.naturalWidth * scale));
    canvas.height = Math.max(1, Math.ceil(image.naturalHeight * scale));
    canvas.getContext('2d').drawImage(image, 0, 0, canvas.width, canvas.height);
    var data = canvas.toDataURL('image/png');
    return {data:data, width:canvas.width, height:canvas.height};
  }
  async function formula(element) {
    var key = element.outerHTML;
    if (cache.has(key)) return cache.get(key);
    if (cache.size >= 1000) throw Error('Too many formulas');
    var stage = document.createElement('div');
    stage.style.cssText = 'position:absolute;left:0;top:0;display:inline-block;white-space:nowrap;font:16px serif;line-height:normal;padding:4px;background:transparent;color:black;';
    var clone = element.cloneNode(true);
    clone.querySelectorAll('.katex-display').forEach(function (e) { e.style.cssText='display:inline-block;margin:0;padding:4px 0;overflow:visible;'; });
    var marker = document.createElement('span'); marker.style.cssText='display:inline-block;width:0;height:0;';
    stage.appendChild(clone); stage.appendChild(marker); document.body.appendChild(stage);
    try {
      await document.fonts.ready;
      var rect = stage.getBoundingClientRect(), base = marker.getBoundingClientRect().top - rect.top;
      var width = Math.ceil(rect.width), height = Math.ceil(rect.height);
      if (!(width > 0 && height > 0 && width <= 20000 && height <= 20000)) throw Error('Invalid formula size');
      var scale = Math.min(3, 4096 / Math.max(width,height), Math.sqrt(3900000/(width*height)));
      var html = new XMLSerializer().serializeToString(stage);
      var svg = '<svg xmlns="http://www.w3.org/2000/svg" width="'+Math.ceil(width*scale)+'" height="'+Math.ceil(height*scale)+'" viewBox="0 0 '+width+' '+height+'"><foreignObject width="100%" height="100%"><div xmlns="http://www.w3.org/1999/xhtml"><style>'+fontCss.replace(/&/g,'&amp;').replace(/</g,'&lt;')+'</style>'+html+'</div></foreignObject></svg>';
      var asset = await raster('data:image/svg+xml;base64,'+btoa(unescape(encodeURIComponent(svg))),4096);
      asset.width = width; asset.height = height; asset.baseline = base;
      var id = 'formula-' + cache.size;
      pendingAssets.push(Object.assign({id:id},asset));
      var reference = {ref:id, width:width, height:height, baseline:base};
      cache.set(key,reference); return reference;
    } finally { stage.remove(); }
  }
  async function blocks(root, inherited) {
    var result = [], runs = [], style = inherited || {};
    function flush() { if (runs.some(function(r){return r.image || r.text.trim();})) result.push({kind:'text',runs:runs}); runs=[]; }
    async function visit(node, s) {
      if (node.nodeType===3) {
        var t=node.textContent; characters+=t.length;
        if(characters>500000) throw Error('PDF text limit');
        if(t) runs.push(Object.assign({text:t},s)); return;
      }
      if(node.nodeType!==1 || node.matches('.pick,.think,.continue,summary,script,style')) return;
      if(node.matches('.math-source')) {
        var asset=await formula(node);
        if(node.querySelector('.katex-display')) {flush();result.push({kind:'math',image:asset});}
        else runs.push({image:asset});
        return;
      }
      if(node.tagName==='BR') {flush();return;}
      if(node.tagName==='HR') {flush();result.push({kind:'rule'});return;}
      if(node.tagName==='IMG') {flush();result.push({kind:'image',image:await raster(node.src,1800)});return;}
      if(node.tagName==='TABLE') {
        flush();var rows=[],headerRows=0;
        for(var row of node.rows) {
          // Only leading column headers repeat; a TH used as a body row label
          // must not turn the first data row into a repeated header.
          var isHeader=row.parentElement.tagName==='THEAD' ||
            (row.cells.length>0 && Array.from(row.cells).every(function(cell){return cell.tagName==='TH' && !/^row(group)?$/i.test(cell.getAttribute('scope')||'');}));
          if(rows.length===headerRows && isHeader) headerRows++;
          var cells=[];
          for(var cell of row.cells) cells.push(await blocks(cell,Object.assign({},s,{bold:cell.tagName==='TH'||s.bold})));
          rows.push(cells);
        }
        result.push({kind:'table',rows:rows,headerRows:headerRows});return;
      }
      if(node.matches('.quoted-text')) {flush();result.push({kind:'quote',blocks:await blocks(node,s)});return;}
      if(node.tagName==='UL'||node.tagName==='OL') {
        flush();var i=1;
        for(var li of node.children) {
          var content=await blocks(li,s), prefix=node.tagName==='OL'?((li.getAttribute('value')||i)+'. '):'• ';
          if(content[0]?.kind==='text') content[0].runs.unshift({text:prefix});
          else content.unshift({kind:'text',runs:[{text:prefix}]});
          result.push({kind:'indent',blocks:content});i++;
        } return;
      }
      var heading=/^H[1-6]$/.test(node.tagName), separate=heading || ['DIV','P','PRE','FIGURE','FIGCAPTION','DETAILS'].includes(node.tagName);
      if(separate) flush();
      var next=Object.assign({},s);
      if(heading||node.tagName==='STRONG'||node.tagName==='B') next.bold=true;
      if(heading) next.size=15;
      if(node.tagName==='EM'||node.tagName==='I') next.italic=true;
      if(node.tagName==='CODE'||node.tagName==='PRE') next.mono=true;
      for(var child of node.childNodes) await visit(child,next);
      if(separate) flush();
    }
    for(var child of root.childNodes) await visit(child,style);
    flush();return result;
  }
  window.prepareConversationPdf = function (input, css) {
    window.conversationPdfState={status:'working'};
    (async function () {
      fontCss=css;characters=0;cache.clear();messages=[];pendingAssets=[];
      document.body.className='';document.body.style.cssText='background:white;color:black;font:16px serif;';
      var style=document.createElement('style');style.textContent=css;document.head.appendChild(style);
      document.getElementById('out').innerHTML='';
      var totalBytes=0;
      for(var message of input) {
        var root=document.createElement('div');root.innerHTML=bubbleHtml(Object.assign({},message,{thinking:null}),0);
        var converted={user:message.role!=='assistant',blocks:await blocks(root),assets:pendingAssets};
        pendingAssets=[];
        totalBytes+=JSON.stringify(converted).length;
        if(totalBytes>64*1024*1024) throw Error('PDF asset limit');
        messages.push(converted);
      }
      window.conversationPdfState={status:'ready',count:messages.length};
    })().catch(function () {window.conversationPdfState={status:'error'};});return true;
  };
  window.conversationPdfMessage=function(index){return messages[index];};
  // Native pagination consumes messages in order; release JS references immediately afterwards.
  window.conversationPdfTakeMessage=function(index){var message=messages[index];messages[index]=null;return message;};
})();
