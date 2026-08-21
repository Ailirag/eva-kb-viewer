/*
 * Сводка базы знаний: разделы, две карточки и столбиковая диаграмма.
 * Всё локально: ни fetch, ни внешних библиотек — данные лежат прямо здесь
 * (при file:// XHR к соседним файлам запрещён, поэтому data.json — только
 * человекочитаемая копия того же набора).
 */
(function () {
  'use strict';

  var REPORT = {
    updatedAt: '21.08.2026, 09:30',
    articles: { value: 1284, delta: '+96 за месяц' },
    requests: { value: 3417, delta: '+12% к июлю' },
    months: [
      { label: 'янв', value: 1980 },
      { label: 'фев', value: 2140 },
      { label: 'мар', value: 2460 },
      { label: 'апр', value: 2310 },
      { label: 'май', value: 2705 },
      { label: 'июн', value: 2890 },
      { label: 'июл', value: 3050 },
      { label: 'авг', value: 3417 },
      { label: 'сен', value: 3180 },
      { label: 'окт', value: 2960 },
      { label: 'ноя', value: 2740 },
      { label: 'дек', value: 2205 }
    ]
  };

  var SVG_NS = 'http://www.w3.org/2000/svg';

  function el(name, attrs) {
    var node = document.createElementNS(SVG_NS, name);
    for (var key in attrs) {
      if (Object.prototype.hasOwnProperty.call(attrs, key)) {
        node.setAttribute(key, String(attrs[key]));
      }
    }
    return node;
  }

  function format(n) {
    return String(n).replace(/\B(?=(\d{3})+(?!\d))/g, ' ');
  }

  /** Округляет максимум до «чистого» значения для оси. */
  function niceMax(max) {
    var step = Math.pow(10, Math.floor(Math.log(max) / Math.LN10) - 1) * 5;
    return Math.ceil(max / step) * step;
  }

  /** Столбик: скруглён на 4px сверху, прямой у базовой линии. */
  function barPath(x, y, width, height) {
    var r = Math.min(4, width / 2, height);
    var bottom = y + height;
    return 'M' + x + ' ' + bottom +
      ' L' + x + ' ' + (y + r) +
      ' Q' + x + ' ' + y + ' ' + (x + r) + ' ' + y +
      ' L' + (x + width - r) + ' ' + y +
      ' Q' + (x + width) + ' ' + y + ' ' + (x + width) + ' ' + (y + r) +
      ' L' + (x + width) + ' ' + bottom + ' Z';
  }

  function renderStats() {
    document.getElementById('updated-at').textContent = REPORT.updatedAt;
    document.getElementById('stat-articles').textContent = format(REPORT.articles.value);
    document.getElementById('stat-articles-delta').textContent = REPORT.articles.delta;
    document.getElementById('stat-requests').textContent = format(REPORT.requests.value);
    document.getElementById('stat-requests-delta').textContent = REPORT.requests.delta;
  }

  function renderTable() {
    var body = document.getElementById('table-body');
    var rows = '';
    REPORT.months.forEach(function (m) {
      rows += '<tr><td>' + m.label + '</td><td class="num">' + format(m.value) + '</td></tr>';
    });
    body.innerHTML = rows;
  }

  function renderChart() {
    var host = document.getElementById('chart');
    var tooltip = document.getElementById('tooltip');
    var data = REPORT.months;

    var W = 340, H = 220;
    var pad = { top: 18, right: 6, bottom: 26, left: 34 };
    var plotW = W - pad.left - pad.right;
    var plotH = H - pad.top - pad.bottom;

    var top = niceMax(Math.max.apply(null, data.map(function (m) { return m.value; })));
    var maxValue = Math.max.apply(null, data.map(function (m) { return m.value; }));

    var svg = el('svg', {
      viewBox: '0 0 ' + W + ' ' + H,
      role: 'img',
      'aria-label': 'Столбиковая диаграмма обращений по месяцам за 2026 год'
    });

    // Сетка и подписи оси: тонкие, сплошные, приглушённые.
    var ticks = 4;
    for (var i = 0; i <= ticks; i++) {
      var value = top / ticks * i;
      var y = pad.top + plotH - plotH * (i / ticks);
      svg.appendChild(el('line', {
        x1: pad.left, y1: y, x2: W - pad.right, y2: y,
        stroke: i === 0 ? 'var(--axis)' : 'var(--grid)', 'stroke-width': 1
      }));
      var tick = el('text', { x: pad.left - 6, y: y + 3.5, 'text-anchor': 'end' });
      tick.setAttribute('class', 'tick-label');
      tick.textContent = format(Math.round(value));
      svg.appendChild(tick);
    }

    var band = plotW / data.length;
    var barW = Math.min(24, band * 0.6);

    data.forEach(function (m, index) {
      var height = plotH * (m.value / top);
      var x = pad.left + band * index + (band - barW) / 2;
      var y = pad.top + plotH - height;

      svg.appendChild(el('path', {
        d: barPath(x, y, barW, height),
        fill: 'var(--series-1)'
      }));

      var label = el('text', {
        x: pad.left + band * index + band / 2,
        y: H - pad.bottom + 15,
        'text-anchor': 'middle'
      });
      label.setAttribute('class', 'tick-label');
      label.textContent = m.label;
      svg.appendChild(label);

      // Подписываем только максимум — остальное читается по оси и подсказке.
      if (m.value === maxValue) {
        var peak = el('text', {
          x: pad.left + band * index + band / 2,
          y: y - 6,
          'text-anchor': 'middle'
        });
        peak.setAttribute('class', 'bar-label');
        peak.textContent = format(m.value);
        svg.appendChild(peak);
      }

      // Зона наведения шире столбика: попасть проще.
      var hit = el('rect', {
        x: pad.left + band * index,
        y: pad.top,
        width: band,
        height: plotH,
        fill: 'transparent'
      });
      hit.addEventListener('mouseenter', function () { showTooltip(m, x + barW / 2, y); });
      hit.addEventListener('mouseleave', hideTooltip);
      hit.addEventListener('click', function () { showTooltip(m, x + barW / 2, y); });
      svg.appendChild(hit);
    });

    host.appendChild(svg);

    function showTooltip(m, svgX, svgY) {
      var scale = host.clientWidth / W;
      tooltip.innerHTML = m.label + ' · <span class="tt-value">' + format(m.value) + '</span>';
      tooltip.style.left = (host.offsetLeft + svgX * scale) + 'px';
      tooltip.style.top = (host.offsetTop + svgY * scale - 6) + 'px';
      tooltip.hidden = false;
    }

    function hideTooltip() {
      tooltip.hidden = true;
    }
  }

  function initNav() {
    var buttons = [].slice.call(document.querySelectorAll('.nav-item'));

    function select(button) {
      buttons.forEach(function (other) {
        var active = other === button;
        other.classList.toggle('is-active', active);
        other.setAttribute('aria-selected', active ? 'true' : 'false');
        document.getElementById(other.getAttribute('data-section'))
          .classList.toggle('is-active', active);
      });
    }

    buttons.forEach(function (button) {
      button.addEventListener('click', function () { select(button); });
    });

    // Раздел можно открыть сразу: index.html#dynamics
    var fromHash = document.querySelector(
      '.nav-item[data-section="' + String(location.hash).replace('#', '') + '"]'
    );
    if (fromHash) {
      select(fromHash);
    }
  }

  renderStats();
  renderTable();
  renderChart();
  initNav();
})();
