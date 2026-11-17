/* Interaction only: the SVG sanitizer owns image generation; this viewer never inserts SVG markup. */
(function () {
  'use strict';
  var viewer = null, priorFocus = null, priorOverflow = '', scale = 1, x = 0, y = 0;
  function interactive() { return window.Android && Android.diagramViewerChanged; }
  function label(name, fallback) { return (window.THINK_LABELS || {})[name] || fallback; }
  function notify(open) { try { if (interactive()) Android.diagramViewerChanged(open); } catch (ignored) {} }
  function paint() {
    if (!viewer) return;
    var maxX = viewer.stage.clientWidth * (scale - 1) / 2;
    var maxY = viewer.stage.clientHeight * (scale - 1) / 2;
    x = Math.max(-maxX, Math.min(maxX, x)); y = Math.max(-maxY, Math.min(maxY, y));
    viewer.image.style.transform = 'translate(' + x + 'px,' + y + 'px) scale(' + scale + ')';
    viewer.count.textContent = scale.toFixed(1) + '×';
  }
  window.dshCloseDiagram = function () {
    if (!viewer) return;
    viewer.root.remove(); viewer = null;
    document.documentElement.style.overflow = priorOverflow;
    var out = document.getElementById('out'); if (out) out.inert = false;
    if (priorFocus && priorFocus.isConnected) priorFocus.focus({preventScroll: true});
    priorFocus = null;
    notify(false);
  };
  function open(image) {
    // Only images emitted by dshRenderSvg inside a rendered message can enter this viewer.
    // The data URI stays an <img>: no raw markup, URL navigation, network or native data bridge.
    if (!interactive() || !image.matches('.bubble .ai-diagram > img') ||
        !/^data:image\/svg\+xml;base64,[A-Za-z0-9+/]+=*$/.test(image.getAttribute('src') || '')) return;
    window.dshCloseDiagram();
    if (window.dshNoFollow) window.dshNoFollow();
    if (window.dshResetGestures) window.dshResetGestures();
    priorFocus = document.activeElement; priorOverflow = document.documentElement.style.overflow;
    document.documentElement.style.overflow = 'hidden';
    var out = document.getElementById('out'); if (out) out.inert = true;
    var root = document.createElement('div'), stage = document.createElement('div');
    root.setAttribute('role', 'dialog'); root.setAttribute('aria-modal', 'true');
    root.setAttribute('aria-label', image.alt || label('mediaPreview', '查看大图'));
    root.style.cssText = 'position:fixed;inset:0;z-index:10000;background:#111;color:white;display:flex;flex-direction:column;touch-action:none;user-select:none;';
    stage.style.cssText = 'flex:1;min-height:0;display:flex;align-items:center;justify-content:center;overflow:hidden;padding:12px;';
    var enlarged = document.createElement('img');
    enlarged.src = image.getAttribute('src'); enlarged.alt = image.alt;
    enlarged.draggable = false;
    enlarged.style.cssText = 'display:block;width:100%;height:100%;object-fit:contain;background:white;transform-origin:center;pointer-events:none;';
    stage.appendChild(enlarged);
    var bar = document.createElement('div'), count = document.createElement('span');
    bar.style.cssText = 'display:flex;align-items:center;justify-content:center;gap:16px;padding:8px;flex:none;';
    function button(text, description, action) {
      var b = document.createElement('button'); b.type = 'button'; b.textContent = text;
      b.setAttribute('aria-label', description);
      b.style.cssText = 'min-width:48px;min-height:48px;font-size:22px;background:#333;color:white;border:0;border-radius:8px;';
      b.addEventListener('click', action); bar.appendChild(b); return b;
    }
    button('−', label('mediaZoomOut', '缩小'), function () { scale = Math.max(1, scale / 1.5); paint(); });
    bar.appendChild(count);
    button('+', label('mediaZoomIn', '放大'), function () { scale = Math.min(6, scale * 1.5); paint(); });
    var close = button('✕', label('mediaClose', '关闭'), window.dshCloseDiagram);
    root.appendChild(stage); root.appendChild(bar); document.body.appendChild(root);
    viewer = {root:root, stage:stage, image:enlarged, count:count}; scale = 1; x = 0; y = 0;
    var points = new Map(), previous = null;
    function geometry() {
      var p = Array.from(points.values());
      if (!p.length) return null;
      return p.length < 2 ? {x:p[0].x, y:p[0].y, distance:0, count:1} : {
        x:(p[0].x+p[1].x)/2, y:(p[0].y+p[1].y)/2,
        distance:Math.hypot(p[0].x-p[1].x, p[0].y-p[1].y), count:2};
    }
    stage.addEventListener('pointerdown', function (e) {
      e.preventDefault(); stage.setPointerCapture(e.pointerId);
      points.set(e.pointerId, {x:e.clientX, y:e.clientY}); previous = geometry();
    });
    stage.addEventListener('pointermove', function (e) {
      if (!points.has(e.pointerId)) return;
      points.set(e.pointerId, {x:e.clientX, y:e.clientY}); var now = geometry();
      if (previous && previous.count === now.count) {
        if (now.count > 1 && previous.distance > 0) scale = Math.max(1, Math.min(6, scale * now.distance / previous.distance));
        if (scale > 1) { x += now.x - previous.x; y += now.y - previous.y; }
        paint();
      }
      previous = now;
    });
    function lift(e) { points.delete(e.pointerId); previous = geometry(); }
    stage.addEventListener('pointerup', lift); stage.addEventListener('pointercancel', lift);
    stage.addEventListener('lostpointercapture', lift);
    root.addEventListener('keydown', function (e) {
      if (e.key === 'Escape') { e.preventDefault(); window.dshCloseDiagram(); }
      else if (e.key === 'Tab') {
        var buttons = Array.from(bar.querySelectorAll('button')), index = buttons.indexOf(document.activeElement);
        e.preventDefault(); buttons[(index + (e.shiftKey ? buttons.length - 1 : 1)) % buttons.length].focus();
      }
    });
    paint(); close.focus({preventScroll:true}); notify(true);
  }
  function activate(e) {
    var image = e.target && e.target.closest && e.target.closest('.ai-diagram > img');
    if (!image || window.selectionMode || !interactive()) return;
    e.preventDefault(); e.stopPropagation(); open(image);
  }
  document.addEventListener('click', activate, true);
  document.addEventListener('keydown', function (e) { if (e.key === 'Enter' || e.key === ' ') activate(e); }, true);
  window.dshDecorateDiagrams = function () {
    if (!interactive()) return;
    document.querySelectorAll('#out .ai-diagram > img').forEach(function (image) {
      image.tabIndex = 0; image.setAttribute('role', 'button'); image.style.cursor = 'zoom-in';
      image.setAttribute('aria-label', (image.alt ? image.alt + ': ' : '') + label('mediaPreview', '查看大图'));
    });
  };
})();
