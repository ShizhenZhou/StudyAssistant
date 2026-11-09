/* AI SVG is data, never executable markup in the conversation document. */
(function(){
  'use strict';
  var NS='http://www.w3.org/2000/svg', XMLNS='http://www.w3.org/2000/xmlns/';
  var cache=new Map();
  var shapes={
    svg:['viewBox','width','height','preserveAspectRatio'], g:[], defs:[],
    path:['d','fill-rule'], line:['x1','y1','x2','y2'],
    polyline:['points'], polygon:['points','fill-rule'],
    rect:['x','y','width','height','rx','ry'], circle:['cx','cy','r'], ellipse:['cx','cy','rx','ry'],
    text:['x','y','dx','dy','font-size','font-family','font-weight','font-style','text-anchor','dominant-baseline'],
    tspan:['x','y','dx','dy','font-size','font-family','font-weight','font-style','text-anchor','dominant-baseline'],
    title:[], desc:[],
    marker:['viewBox','refX','refY','markerWidth','markerHeight','markerUnits','orient','preserveAspectRatio'],
    linearGradient:['x1','y1','x2','y2','gradientUnits','gradientTransform','spreadMethod'],
    radialGradient:['cx','cy','r','fx','fy','fr','gradientUnits','gradientTransform','spreadMethod'],
    stop:['offset','stop-color','stop-opacity']
  };
  var common=['id','fill','stroke','stroke-width','stroke-linecap','stroke-linejoin','stroke-dasharray',
    'stroke-dashoffset','opacity','fill-opacity','stroke-opacity','transform','vector-effect',
    'marker-start','marker-mid','marker-end','font-size','font-family','font-weight','font-style',
    'text-anchor','dominant-baseline'];
  var enums={
    'stroke-linecap':['butt','round','square'], 'stroke-linejoin':['miter','round','bevel'],
    'fill-rule':['nonzero','evenodd'], 'vector-effect':['none','non-scaling-stroke'],
    'font-weight':['normal','bold','100','200','300','400','500','600','700','800','900'],
    'font-style':['normal','italic','oblique'], 'text-anchor':['start','middle','end'],
    'dominant-baseline':['auto','middle','central','hanging','alphabetic','text-before-edge','text-after-edge'],
    'gradientUnits':['objectBoundingBox','userSpaceOnUse'], 'spreadMethod':['pad','reflect','repeat'],
    'markerUnits':['strokeWidth','userSpaceOnUse']
  };
  function numbers(value){
    var list=value.trim().split(/[\s,]+/);
    if(!list.length || !list.every(function(n){return /^[+-]?(?:\d+\.?\d*|\.\d+)(?:e[+-]?\d+)?$/i.test(n) && Number.isFinite(+n) && Math.abs(+n)<=100000;})) return null;
    return list.map(Number);
  }
  function color(value){
    return /^(?:none|currentColor|transparent|[a-zA-Z]{1,24}|#[0-9a-fA-F]{3,8}|(?:rgb|rgba|hsl|hsla)\([0-9.,%\s+-]+\))$/.test(value);
  }
  function clean(raw){
    if(raw.length>65536 || new TextEncoder().encode(raw).length>65536 || /<!|<\?/.test(raw)) return null;
    var source=new DOMParser().parseFromString(raw,'image/svg+xml'),root=source.documentElement;
    if(!root || root.localName!=='svg' || source.querySelector('parsererror')) return null;
    var doc=document.implementation.createDocument(NS,'svg',null),target=doc.documentElement;
    var ids=new Map(),refs=[],count=0;
    function attr(name,value,inDefinition){
      if(value.length>8192) return false;
      if(name==='id') return /^[A-Za-z][A-Za-z0-9_-]{0,63}$/.test(value);
      if(name==='fill' || name==='stroke' || name==='stop-color' || name.startsWith('marker-')){
        var ref=value.match(/^url\(#([A-Za-z][A-Za-z0-9_-]{0,63})\)$/);
        if(ref){
          // Definitions cannot refer back to one another (including recursive markers).
          if(inDefinition) return false;
          refs.push({id:ref[1],marker:name.startsWith('marker-')});return true;
        }
        return name.startsWith('marker-') ? value==='none' : color(value);
      }
      if(enums[name]) return enums[name].includes(value);
      if(name==='font-family') return /^[A-Za-z\s,'-]{1,100}$/.test(value);
      if(name==='preserveAspectRatio') return /^(?:none|x(?:Min|Mid|Max)Y(?:Min|Mid|Max)(?: (?:meet|slice))?)$/.test(value);
      if(name==='orient') return value==='auto' || value==='auto-start-reverse' || (numbers(value)||[]).length===1;
      if(name==='d'){
        if(!/^[MmZzLlHhVvCcSsQqTtAa0-9eE.,+\s-]+$/.test(value)) return false;
        return (value.match(/[+-]?(?:\d+\.?\d*|\.\d+)(?:e[+-]?\d+)?/gi)||[]).every(function(n){return Number.isFinite(+n)&&Math.abs(+n)<=100000;});
      }
      if(name==='transform' || name==='gradientTransform'){
        if(!/^(?:\s*(?:matrix|translate|scale|rotate|skewX|skewY)\([0-9eE.,+\s-]+\)\s*)+$/.test(value)) return false;
        return (value.match(/\(([^)]*)\)/g)||[]).every(function(n){return numbers(n.slice(1,-1))!==null;});
      }
      if(name==='stroke-dasharray' && value==='none') return true;
      var n=numbers(value.replace(/(?:px|%)$/,''));
      if(!n) return false;
      if(name==='viewBox') return n.length===4 && n[2]>0 && n[3]>0 && n[2]<=4096 && n[3]<=4096 && n[2]/n[3]>=.1 && n[2]/n[3]<=10;
      if(name==='points') return n.length>=4 && n.length%2===0;
      if(name==='stroke-dasharray') return n.length<=32 && n.every(function(x){return x>=0;});
      if(name==='x' || name==='y' || name==='dx' || name==='dy') return n.length<=128;
      if(n.length!==1) return false;
      if(name.includes('opacity')) return n[0]>=0 && n[0]<=1;
      if(name==='stroke-width') return n[0]>=0 && n[0]<=32;
      if(name==='font-size') return n[0]>=1 && n[0]<=160;
      if(['width','height','markerWidth','markerHeight','r','rx','ry','fr'].includes(name)) return n[0]>=0 && n[0]<=4096;
      return true;
    }
    function copy(node,parent,depth,inDefinition){
      if(node.nodeType===3){parent.appendChild(doc.createTextNode(node.nodeValue));return;}
      if(node.nodeType!==1 || depth>20 || ++count>512) throw Error('Unsupported SVG node');
      var tag=node.localName;
      if(!Object.prototype.hasOwnProperty.call(shapes,tag) || node.prefix || (node.namespaceURI && node.namespaceURI!==NS) || (tag==='svg' && depth!==0)) throw Error('Unsupported SVG element');
      var nestedDefinition=inDefinition || ['defs','marker','linearGradient','radialGradient'].includes(tag);
      var el=depth===0 ? target : doc.createElementNS(NS,tag);
      for(var a of node.attributes){
        if(a.name==='xmlns' && a.value===NS) continue;
        if(a.namespaceURI || a.name==='xmlns' || (!common.includes(a.name) && !shapes[tag].includes(a.name)) || !attr(a.name,a.value,nestedDefinition)) throw Error('Unsupported SVG attribute');
        el.setAttribute(a.name,a.value);
      }
      if(el.id){if(ids.has(el.id)) throw Error('Duplicate SVG id');ids.set(el.id,tag);}
      for(var child of node.childNodes) copy(child,el,depth+1,nestedDefinition);
      if(depth) parent.appendChild(el);
    }
    copy(root,target,0,false);
    if(!refs.every(function(r){return r.marker ? ids.get(r.id)==='marker' : ['linearGradient','radialGradient'].includes(ids.get(r.id));})) return null;
    if(!target.hasAttribute('viewBox')){
      var w=numbers((target.getAttribute('width')||'').replace(/px$/,'')),h=numbers((target.getAttribute('height')||'').replace(/px$/,''));
      if(!w||!h||w.length!==1||h.length!==1||!attr('viewBox','0 0 '+w[0]+' '+h[0],false)) return null;
      target.setAttribute('viewBox','0 0 '+w[0]+' '+h[0]);
    }
    target.removeAttribute('width');target.removeAttribute('height');
    var box=numbers(target.getAttribute('viewBox'));
    // Give the image a bounded, explicit raster viewport and stable intrinsic aspect ratio.
    var width=800,height=Math.round(width*box[3]/box[2]);
    if(height>1600){width=Math.round(width*1600/height);height=1600;}
    target.setAttribute('width',String(width));target.setAttribute('height',String(height));
    target.setAttributeNS(XMLNS,'xmlns',NS);
    var title=(target.querySelector('title')?.textContent || '').trim().slice(0,200);
    var xml=new XMLSerializer().serializeToString(target),bytes=new TextEncoder().encode(xml),binary='';
    for(var b of bytes) binary+=String.fromCharCode(b);
    function escape(s){return s.replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;').replace(/"/g,'&quot;');}
    return '<figure class="ai-diagram"><img width="'+width+'" height="'+height+'" alt="'+escape(title)+'" src="data:image/svg+xml;base64,'+btoa(binary)+'">'+(title?'<figcaption>'+escape(title)+'</figcaption>':'')+'</figure>';
  }
  window.dshRenderSvg=function(raw){
    if(cache.has(raw)) return cache.get(raw);
    var result=null;
    try{result=clean(raw);}catch(ignored){}
    if(raw.length<=65536){
      if(cache.size>=8) cache.delete(cache.keys().next().value);
      cache.set(raw,result);
    }
    return result;
  };
})();
