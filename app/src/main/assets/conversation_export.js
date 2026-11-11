/* Local, bounded-page export. Uses the same sanitized Markdown/KaTeX/SVG as the conversation. */
(function () {
  'use strict';
  var pages = [], viewport, output, pageHeight, footer, pageScale = 1;
  var pdfText = [];
  function logicalRect(rect) {
    return {left:rect.left/pageScale, top:rect.top/pageScale, bottom:rect.bottom/pageScale,
      width:rect.width/pageScale, height:rect.height/pageScale};
  }
  function bounds(el) { return logicalRect(el.getBoundingClientRect()); }
  window.conversationExportState = {status: 'idle'};

  // Every source interval is covered exactly once. Move cuts above overlapping ink/atomic blocks.
  function paginate(height, capacity, intervals) {
    var result = [], start = 0;
    intervals.sort(function (a, b) { return a[0] - b[0] || a[1] - b[1]; });
    var merged = [];
    intervals.forEach(function (r) {
      var last = merged[merged.length - 1];
      if (last && r[0] < last[1] - 0.1) last[1] = Math.max(last[1], r[1]);
      else merged.push(r.slice());
    });
    var cursor = 0;
    var inkEnd = intervals.reduce(function (end, r) { return Math.max(end, r[1]); }, 0);
    while (start < height - 0.1) {
      if (result.length && start >= inkEnd) break; // Do not emit a page containing only trailing padding.
      var end = Math.min(height, start + capacity);
      while (cursor < merged.length && merged[cursor][1] <= start) cursor++;
      for (var i = cursor; i < merged.length && merged[i][0] < end; i++) {
        if (merged[i][1] > end + 0.1) { end = merged[i][0]; break; }
      }
      if (end <= start + 0.1 || result.length >= 200) throw new Error('Export block/page limit');
      result.push([start, end]); start = end;
    }
    return result;
  }
  window.conversationExportPaginate = paginate;

  window.prepareConversationExport = function (messages, format) {
    window.conversationExportState = {status: 'working'};
    (async function () {
      if (!Array.isArray(messages) || !messages.length) throw new Error('No messages');
      document.body.className = 'export-mode';
      document.documentElement.style.setProperty('--k', '1');
      // Fixed document layout, scaled only for the preview/capture. A4 body text is 12pt.
      var layoutWidth = format === 'PDF' ? 794 : 540;
      pageScale = window.innerWidth / layoutWidth;
      var layoutHeight = window.innerHeight / pageScale;
      selectionMode = false; following = false; window.thinkForced = 'closed';
      var style = document.createElement('style');
      style.textContent = 'html,body{height:100%;overflow:hidden!important;background:#fff!important;margin:0;padding:0;}' +
        'body{font-family:sans-serif;font-size:16px;line-height:1.6;}' +
        '#export-page{position:fixed;top:0;left:0;transform-origin:top left;}' +
        '#export-window{position:absolute;top:32px;left:32px;right:32px;overflow:hidden;}' +
        '#out{display:block!important;position:relative;width:100%;margin:0;padding:0;}' +
        '.bubble{box-sizing:border-box;width:100%;max-width:none;margin:0 0 12px;padding:10px 12px;font-size:' + (format === 'PDF' ? 16 : 14) + 'px;box-shadow:none!important;}' +
        '.bubble.assistant{border:1px solid #b8b8b8;}.bubble.user{border:1px solid #9bc49b;}' +
        '.export-graphics .pdf-text{color:transparent!important;text-shadow:none!important;text-decoration-color:transparent!important;}' +
        '.export-graphics #export-footer{visibility:hidden;}' +
        '.pick,.think,.continue,summary,.quote-jump-highlight{display:none!important;}' +
        '.msgimg,.ai-diagram img{height:auto;max-width:100%;object-fit:contain;}' +
        '.table-scroll,.katex-display,pre{overflow:visible!important;}' +
        'pre{white-space:pre-wrap!important;overflow-wrap:anywhere!important;}' +
        '#export-footer{position:absolute;bottom:14px;left:0;right:0;text-align:center;color:#777;font:11px sans-serif;}' +
        '*{animation:none!important;transition:none!important;pointer-events:none!important;}';
      document.head.appendChild(style);
      output = document.getElementById('out');
      output.innerHTML = messages.map(function (m, i) {
        return bubbleHtml(Object.assign({}, m, {thinking:null, thinkingOpen:false}), i);
      }).join('');
      output.querySelectorAll('details').forEach(function (el) { el.open = true; });
      var sheet = document.createElement('div'); sheet.id = 'export-page';
      sheet.style.width = layoutWidth + 'px'; sheet.style.height = layoutHeight + 'px';
      sheet.style.transform = 'scale(' + pageScale + ')'; document.body.appendChild(sheet);
      viewport = document.createElement('div'); viewport.id = 'export-window';
      sheet.appendChild(viewport); viewport.appendChild(output);
      footer = document.createElement('div'); footer.id = 'export-footer'; sheet.appendChild(footer);
      pageHeight = layoutHeight - 72;
      if (window.innerWidth < 150 || pageHeight < 150) throw new Error('Export viewport too small');
      // Leave space for bubble/quote padding around tall formulas and diagrams.
      var blockHeight = format === 'PDF' ? pageHeight - 128 : pageHeight - 24;
      output.querySelectorAll('img').forEach(function (img) {
        img.style.maxHeight = Math.min(pageHeight - 40, blockHeight) + 'px'; img.loading = 'eager';
      });
      await Promise.all(Array.from(output.querySelectorAll('img')).map(function (img) {
        return img.decode().then(function () { if (!img.naturalWidth) throw new Error('Image unavailable'); });
      }));
      void output.offsetHeight; // Force font discovery/layout; native capture separately waits for a painted frame.
      await document.fonts.ready;
      // Inline math is also unbreakable. Fit oversized formulas without wrapping display math twice.
      output.querySelectorAll('.katex').forEach(function (math) {
        if (math.closest('.katex-display') || math.parentElement.closest('.katex')) return;
        var parent = math.closest('td,th,.quoted-text,.message-text') || output;
        var padding = getComputedStyle(parent);
        var available = parent.clientWidth - parseFloat(padding.paddingLeft) - parseFloat(padding.paddingRight) - 2;
        var rect = bounds(math);
        var scale = Math.min(1, available / Math.max(1, rect.width), blockHeight / Math.max(1, rect.height));
        if (scale < 1) { math.style.display = 'inline-block'; math.style.whiteSpace = 'nowrap'; math.style.zoom = String(scale); }
      });
      // Scale wide math/tables to include their entire horizontal extent; retain math padding.
      output.querySelectorAll('.katex-display,.table-scroll,pre').forEach(function (box) {
        var available = box.clientWidth, inner = document.createElement('div');
        while (box.firstChild) inner.appendChild(box.firstChild);
        box.appendChild(inner);
        var width = Math.max(available, inner.scrollWidth);
        var scale = Math.min(1, available / width);
        if (box.matches('.katex-display,pre')) scale = Math.min(scale, blockHeight / Math.max(1, inner.offsetHeight));
        if (box.matches('.table-scroll')) {
          inner.querySelectorAll('tr').forEach(function (row) { scale = Math.min(scale, blockHeight / Math.max(1, row.offsetHeight)); });
        }
        if (scale < 1) { inner.style.width = width + 'px'; inner.style.zoom = String(scale); }
      });
      // A diagram's caption/border belongs to the same atomic block as its image.
      output.querySelectorAll('.ai-diagram').forEach(function (figure) {
        var rect = bounds(figure);
        var figureHeight = format === 'PDF' ? blockHeight : pageHeight - 8;
        if (rect.height > figureHeight) {
          figure.style.boxSizing = 'border-box'; figure.style.width = rect.width + 'px';
          figure.style.zoom = String(figureHeight / rect.height);
        }
      });
      await document.fonts.ready;
      void output.offsetHeight;
      if (format === 'PDF') collectPdfText();
      var origin = bounds(output).top, intervals = [];
      function add(rect) {
        rect = logicalRect(rect);
        if (rect.width > 0 && rect.height > 0) intervals.push([Math.max(0, rect.top - origin), rect.bottom - origin]);
      }
      var atomic = '.katex-display,.katex, tr,img,pre,.ai-diagram';
      output.querySelectorAll(atomic).forEach(function (el) {
        if (!el.parentElement.closest(atomic)) add(el.getBoundingClientRect());
      });
      output.querySelectorAll('table').forEach(function (table) {
        var rows = table.querySelectorAll('tr');
        if (rows.length < 2) return;
        var first = bounds(rows[0]), second = bounds(rows[1]);
        if (second.bottom - first.top < pageHeight) intervals.push([first.top - origin, second.bottom - origin]);
      });
      var walker = document.createTreeWalker(output, NodeFilter.SHOW_TEXT);
      while (walker.nextNode()) {
        var node = walker.currentNode, parent = node.parentElement;
        if (!node.textContent.trim() || parent.closest(atomic + ',.think,summary,.continue,.pick')) continue;
        var range = document.createRange(); range.selectNodeContents(node);
        Array.from(range.getClientRects()).forEach(add);
      }
      var height = Math.ceil(bounds(output).height);
      if (height <= 0 || intervals.length === 0) throw new Error('Empty export');
      pages = paginate(height, pageHeight, intervals);
      window.showConversationExportPage(0);
      window.conversationExportState = {status:'ready', pages:pages.length, width:window.innerWidth, height:window.innerHeight};
    })().catch(function () { window.conversationExportState = {status:'error'}; });
    return true;
  };
  // Prose is drawn as native PDF text; math/SVG/images remain in the graphics layer.
  // Wrap text nodes only, never math internals. Inline spans do not change line breaking.
  function collectPdfText() {
    pdfText = [];
    var walker = document.createTreeWalker(output, NodeFilter.SHOW_TEXT), nodes = [];
    while (walker.nextNode()) {
      var node = walker.currentNode;
      if (node.textContent.trim() && !node.parentElement.closest('.katex,script,style,.think,summary,.pick,.continue')) nodes.push(node);
    }
    var context = document.createElement('canvas').getContext('2d');
    var segmenter = new Intl.Segmenter(undefined, {granularity:'grapheme'});
    var origin = bounds(output).top, characters = 0;
    nodes.forEach(function (node) {
      var span = document.createElement('span'); span.className = 'pdf-text';
      node.parentNode.insertBefore(span, node); span.appendChild(node);
      var css = getComputedStyle(span);
      if (css.visibility !== 'visible' || !span.getClientRects().length) return;
      context.font = css.fontStyle + ' ' + css.fontWeight + ' ' + css.fontSize + ' ' + css.fontFamily;
      var metrics = context.measureText('Mg'), fontHeight = metrics.fontBoundingBoxAscent + metrics.fontBoundingBoxDescent;
      if (!(fontHeight > 0)) throw new Error('Unavailable text metrics');
      var run = null, range = document.createRange();
      for (var part of segmenter.segment(node.textContent)) {
        if (++characters > 500000) throw new Error('Export text limit');
        range.setStart(node, part.index); range.setEnd(node, part.index + part.segment.length);
        var r = logicalRect(range.getBoundingClientRect());
        if (r.width <= 0 || r.height <= 0) continue;
        var ratio = r.height / fontHeight;
        var baseline = r.bottom - origin - metrics.fontBoundingBoxDescent * ratio;
        if (run && Math.abs(run.y - baseline) < .25 && Math.abs(run.x + run.width - r.left) < 1) {
          run.text += part.segment; run.width = r.left + r.width - run.x;
        } else {
          run = {text:part.segment, x:r.left, y:baseline, top:r.top-origin, bottom:r.bottom-origin,
            width:r.width, size:parseFloat(css.fontSize)*ratio, color:css.color,
            bold:parseInt(css.fontWeight,10)>=600, italic:css.fontStyle!=='normal',
            mono:css.fontFamily.indexOf('monospace')>=0};
          pdfText.push(run);
        }
      }
    });
  }
  window.conversationExportTextPage = function (index) {
    var page = pages[index];
    if (!page) throw new Error('Invalid page');
    return pdfText.filter(function (r) { return r.top >= page[0]-.2 && r.bottom <= page[1]+.2; })
      .map(function (r) { return Object.assign({}, r, {y:r.y-page[0]+32}); });
  };
  window.conversationExportGraphicsOnly = function (enabled) {
    document.body.classList.toggle('export-graphics', enabled);
    return true;
  };
  window.showConversationExportPage = function (index) {
    var page = pages[index];
    if (!page) throw new Error('Invalid page');
    viewport.style.height = (page[1] - page[0]) + 'px';
    output.style.transform = 'translateY(' + (-page[0]) + 'px)';
    footer.textContent = (index + 1) + ' / ' + pages.length;
    window.scrollTo(0, 0);
    return true;
  };
})();
