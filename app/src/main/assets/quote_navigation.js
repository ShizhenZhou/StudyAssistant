/* Quote anchors use normalized visible text; formulas are atomic original-LaTeX units. */
(function(){
  'use strict';
  function normalized(text){ return (text || '').replace(/\s+/g, ' ').trim(); }
  function boundary(node, after){
    return [node.parentNode, Array.prototype.indexOf.call(node.parentNode.childNodes, node) + (after ? 1 : 0)];
  }
  function textMap(root){
    var text = '', points = [];
    function add(value, point){
      for(var i=0;i<value.length;i++){
        var c = /\s/.test(value[i]) ? ' ' : value[i];
        if(c === ' ' && (!text.length || text[text.length-1] === ' ')) continue;
        text += c; points.push(point(i));
      }
    }
    function walk(node){
      if(node.nodeType === 3){
        add(node.nodeValue, function(i){ return {a:[node,i], b:[node,i+1]}; }); return;
      }
      if(node.nodeType !== 1) return;
      if(node.matches('.continue,.thinkhead,.thinkfold,.pick,img,summary,.quote-jump-highlight')) return;
      if(node.matches('.math-source')){
        var atom = {a:boundary(node,false), b:boundary(node,true)};
        add(node.getAttribute('data-tex') || '', function(){ return atom; }); return;
      }
      var block = /^(DIV|P|LI|H[1-6]|PRE|BLOCKQUOTE)$/.test(node.tagName);
      if(block || node.tagName === 'BR') add(' ', function(){ return {a:[node,0],b:[node,0]}; });
      for(var child=node.firstChild;child;child=child.nextSibling) walk(child);
      if(block) add(' ', function(){ return {a:[node,node.childNodes.length],b:[node,node.childNodes.length]}; });
    }
    walk(root);
    if(text.endsWith(' ')){ text=text.slice(0,-1); points.pop(); }
    return {text:text, points:points};
  }
  function compare(a,b){
    if(a[0] === b[0]) return a[1]-b[1];
    var left=document.createRange(), right=document.createRange();
    left.setStart(a[0],a[1]); left.collapse(true);
    right.setStart(b[0],b[1]); right.collapse(true);
    return left.compareBoundaryPoints(0,right);
  }
  function lowerBound(points, predicate){
    var lo=0, hi=points.length;
    while(lo<hi){ var mid=(lo+hi)>>>1; if(predicate(points[mid])) lo=mid+1; else hi=mid; }
    return lo;
  }
  window.dshQuoteRange = function(scope, range){
    var map=textMap(scope), start=[range.startContainer,range.startOffset], end=[range.endContainer,range.endOffset];
    return {
      start:lowerBound(map.points,function(p){ return compare(p.b,start)<=0; }),
      end:lowerBound(map.points,function(p){ return compare(p.a,end)<0; })
    };
  };
  function scopeName(scope){
    return scope.classList.contains('thinkbody') ? 'thinking' : scope.classList.contains('quoted-text') ? 'quote' : 'body';
  }
  window.dshQuoteScope = scopeName;
  var selectors={body:'.message-text',thinking:'.thinkbody',quote:'.quoted-text'};
  function bubbleAt(idx){ return document.querySelector('.bubble[data-idx="'+idx+'"]'); }
  function scopeAt(idx,scope,detached){
    var bubble=bubbleAt(idx), found=bubble && bubble.querySelector(selectors[scope]);
    if(!found && scope==='thinking' && detached && messageSources[idx].thinking){
      found=document.createElement('div'); found.className='thinkbody'; found.textContent=messageSources[idx].thinking;
    }
    return found;
  }
  function resolve(idx){
    var message=messageSources[idx], anchor=message && message.quoteAnchor, needle=normalized(message && message.quotedText);
    if(!needle) return null;
    if(anchor){
      if(!Object.prototype.hasOwnProperty.call(selectors,anchor.scope)) return null;
      var matches=[];
      for(var i=0;i<idx;i++) if(messageSources[i].messageKey===anchor.messageKey) matches.push(i);
      if(matches.length!==1) return null;
      var source=messageSources[matches[0]], hashes=source.hashes || {};
      if(!anchor.sourceHash || hashes[anchor.scope]!==anchor.sourceHash) return null;
      var scope=scopeAt(matches[0],anchor.scope,true);
      if(!scope) return null;
      var map=textMap(scope), start=anchor.start, end=anchor.end;
      if(!Number.isInteger(start) || !Number.isInteger(end) || start<0 || end<=start || end>map.text.length ||
          normalized(map.text.slice(start,end))!==needle) return null;
      return {idx:matches[0],scope:anchor.scope,start:start,end:end};
    }
    // Legacy quotes carry no source: accept one occurrence across earlier visible message scopes only.
    var result=null;
    for(var index=0;index<idx;index++){
      for(var name of Object.keys(selectors)){
        var candidate=scopeAt(index,name,true);
        if(!candidate) continue;
        var text=textMap(candidate).text, at=text.indexOf(needle);
        if(at<0) continue;
        if(result || text.indexOf(needle,at+1)>=0) return null;
        result={idx:index,scope:name,start:at,end:at+needle.length};
      }
    }
    return result;
  }
  var highlight=null, jumpFrame=null, holdTimer=null, fadeTimer=null;
  function clear(){
    if(jumpFrame!==null) cancelAnimationFrame(jumpFrame);
    clearTimeout(holdTimer); clearTimeout(fadeTimer);
    if(highlight) highlight.layer.remove();
    highlight=null; jumpFrame=null; holdTimer=null; fadeTimer=null;
  }
  window.dshClearQuoteJump=clear;
  window.dshQuoteJumpActive=function(){return highlight!==null;};
  function place(){
    if(!highlight || !highlight.scope.isConnected){ clear(); return null; }
    var range=highlight.range, rects=Array.from(range.getClientRects()).filter(function(r){return r.width>0 && r.height>0;});
    if(!rects.length){ clear(); return null; }
    // Fill each selected line continuously, then join line bands into one clipped layer.
    // A plain bounding rectangle would also tint unquoted text before/after a partial first/last line.
    var bubble=highlight.bubble, bounds=bubble.getBoundingClientRect();
    var bands=[];
    rects.sort(function(a,b){return a.top-b.top || b.bottom-a.bottom;}).forEach(function(r){
      var left=Math.max(bounds.left+bubble.clientLeft,r.left), right=Math.min(bounds.left+bubble.clientLeft+bubble.clientWidth,r.right);
      var top=Math.max(bounds.top+bubble.clientTop,r.top), bottom=Math.min(bounds.top+bubble.clientTop+bubble.clientHeight,r.bottom);
      if(right<=left || bottom<=top) return;
      var last=bands[bands.length-1];
      if(last && top<last.bottom-0.5){
        last.left=Math.min(last.left,left);last.right=Math.max(last.right,right);last.bottom=Math.max(last.bottom,bottom);
      } else bands.push({left:left,right:right,top:top,bottom:bottom});
    });
    if(!bands.length){clear();return null;}
    for(var i=1;i<bands.length;i++){
      var seam=(bands[i-1].bottom+bands[i].top)/2;
      bands[i-1].bottom=seam;bands[i].top=seam;
    }
    var left=Math.min.apply(null,bands.map(function(r){return r.left;})), right=Math.max.apply(null,bands.map(function(r){return r.right;}));
    var top=bands[0].top,bottom=bands[bands.length-1].bottom, outline=[];
    function point(x,y){return (x-left)+'px '+(y-top)+'px';}
    bands.forEach(function(r){outline.push(point(r.right,r.top),point(r.right,r.bottom));});
    bands.slice().reverse().forEach(function(r){outline.push(point(r.left,r.bottom),point(r.left,r.top));});
    Object.assign(highlight.layer.style,{left:(left-bounds.left-bubble.clientLeft)+'px',top:(top-bounds.top-bubble.clientTop)+'px',
      width:Math.max(0,right-left)+'px',height:Math.max(0,bottom-top)+'px',clipPath:'polygon('+outline.join(',')+')'});
    return {top:top,bottom:bottom};
  }
  function settle(){
    jumpFrame=null;
    holdTimer=setTimeout(function(){
      if(highlight) highlight.layer.classList.add('fading');
      fadeTimer=setTimeout(clear,250);
    },2000);
  }
  function unavailable(){ try{ if(window.Android && Android.quoteUnavailable) Android.quoteUnavailable(); }catch(e){} }
  function jump(idx){
    var target=resolve(idx);
    if(!target){ unavailable(); return; }
    clear(); window.dshNoFollow();
    if(target.scope==='thinking'){
      window.thinkUserOpen[target.idx]=1; delete window.thinkUserClosed[target.idx];
      applyThinkOpen(target.idx,true);
    }
    var scope=scopeAt(target.idx,target.scope,false), bubble=bubbleAt(target.idx);
    if(!scope || !bubble){ unavailable(); return; }
    var map=textMap(scope), first=map.points[target.start], last=map.points[target.end-1];
    if(!first || !last){ unavailable(); return; }
    var range=document.createRange(); range.setStart(first.a[0],first.a[1]); range.setEnd(last.b[0],last.b[1]);
    var layer=document.createElement('div'); layer.className='quote-jump-highlight'; layer.setAttribute('aria-hidden','true');
    bubble.appendChild(layer); highlight={layer:layer,scope:scope,bubble:bubble,range:range};
    var startY=window.scrollY, began=performance.now();
    function step(now){
      var rect=place(); if(!rect) return;
      var destination=Math.max(0,Math.min(bottomY(),window.scrollY+rect.top-Math.max(16,(window.innerHeight-Math.min(rect.bottom-rect.top,window.innerHeight-32))/2)));
      var progress=Math.min(1,(now-began)/360), eased=1-Math.pow(1-progress,3);
      window.scrollTo(0,startY+(destination-startY)*eased);
      if(progress<1) jumpFrame=requestAnimationFrame(step); else settle();
    }
    if(window.matchMedia('(prefers-reduced-motion: reduce)').matches) began-=360;
    jumpFrame=requestAnimationFrame(step);
  }
  // A short tap is navigation; selection, dragging, and message multi-select keep their existing behavior.
  var tap=null, suppressClickUntil=0;
  document.addEventListener('touchstart',function(e){
    if(jumpFrame!==null) clear();
    var el=elementOf(e.target), quote=el && el.closest('.quoted-text');
    tap=quote && e.touches.length===1 ? {quote:quote,x:e.touches[0].clientX,y:e.touches[0].clientY,time:performance.now(),moved:false} : null;
  },{passive:true});
  document.addEventListener('touchmove',function(e){
    if(tap && (e.touches.length!==1 || Math.hypot(e.touches[0].clientX-tap.x,e.touches[0].clientY-tap.y)>8)) tap.moved=true;
  },{passive:true});
  document.addEventListener('touchend',function(){
    if(tap && (tap.moved || performance.now()-tap.time>400)) suppressClickUntil=performance.now()+800;
    tap=null;
  },{passive:true});
  document.addEventListener('touchcancel',function(){tap=null;suppressClickUntil=performance.now()+800;},{passive:true});
  document.addEventListener('click',function(e){
    var el=elementOf(e.target), quote=el && el.closest('.quoted-text');
    if(!quote || selectionMode) return;
    var selection=window.getSelection();
    if(performance.now()<suppressClickUntil || (selection && !selection.isCollapsed)) return;
    e.stopPropagation();
    jump(Number(quote.closest('.bubble').getAttribute('data-idx')));
  },true);
  document.addEventListener('scroll',function(){if(highlight) place();},true);
  window.addEventListener('resize',function(){if(highlight) place();});
  document.addEventListener('visibilitychange',function(){if(document.hidden) clear();});
  window.dshQuoteAfterRender=function(){if(selectionMode || (highlight && !highlight.scope.isConnected)) clear();};
})();
