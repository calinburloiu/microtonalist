/**
 * Copyright 2026 Calin-Andrei Burloiu
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */

// The latency report: reads ../results/index.json and the run files it lists, and shows one machine's runs, a run's
// metrics against a "vs" run of the same machine, and on click a metric's timeline, distribution and details.

import { ALPHA, drift, formatNs, MIN_CHANGE_NS, MIN_CHANGE_RATIO } from './stats.js';

const ResultsDir = '../results/';

/** The statistics a timeline can show, the first two by default. */
const Statistics = [
  { key: 'median', label: 'Median', shown: true },
  { key: 'p99', label: 'p99', shown: true },
  { key: 'min', label: 'Min' },
  { key: 'mean', label: 'Mean' },
  { key: 'p90', label: 'p90' },
  { key: 'p95', label: 'p95' },
  { key: 'max', label: 'Max' }
];

const machineSelect = document.getElementById('machine');
const runSelect = document.getElementById('run');
const vsSelect = document.getElementById('vs');
const summary = document.getElementById('summary');
const statusLine = document.getElementById('status');
const tableBody = document.querySelector('#metrics > tbody');

const runFiles = new Map();
let index = null;
let renderCount = 0;

main().catch(error => setStatus(`The report failed: ${error.message}`));

async function main() {
  try {
    index = await fetchJson(`${ResultsDir}index.json`);
  } catch (error) {
    setStatus(`Couldn't load ${ResultsDir}index.json (${error.message}). Serve the perf directory, e.g. with ` +
      '"$JAVA_HOME/bin/jwebserver" -d "$(pwd)/perf", and open /report/.');
    return;
  }
  if (!index.runs?.length) {
    setStatus('No runs yet.');
    return;
  }

  const machines = new Map(byStartTime(index.runs).map(run => [run.machineId, run.machineName ?? run.machineId]));
  fillSelect(machineSelect, [...machines], byStartTime(index.runs).at(-1).machineId);
  machineSelect.addEventListener('change', () => { fillRuns(); render(); });
  runSelect.addEventListener('change', () => { fillVs(); render(); });
  vsSelect.addEventListener('change', render);
  fillRuns();
  await render();
}

// # Selection

function byStartTime(runs) {
  return [...runs].sort((a, b) => a.startTime.localeCompare(b.startTime));
}

/** The index entries of the selected machine's runs, in time order. */
function machineRuns() {
  return byStartTime(index.runs.filter(run => run.machineId === machineSelect.value));
}

function runLabel(entry) {
  return `${formatDate(entry.startTime)} · ${entry.appVersion} ${entry.appCommit.slice(0, 8)}`;
}

function fillRuns() {
  const runs = machineRuns().reverse();
  fillSelect(runSelect, runs.map(run => [run.path, runLabel(run)]), runs[0].path);
  fillVs();
}

/** The "vs" run defaults to the run of the machine right before the selected one. */
function fillVs() {
  const runs = machineRuns();
  const previous = runs[runs.findIndex(run => run.path === runSelect.value) - 1];
  const others = runs.filter(run => run.path !== runSelect.value).reverse();
  fillSelect(vsSelect, [['', 'none'], ...others.map(run => [run.path, runLabel(run)])], previous?.path ?? '');
}

function fillSelect(select, options, selected) {
  select.replaceChildren(...options.map(([value, label]) => element('option', { value, text: label })));
  select.value = selected;
}

// # Loading

async function fetchJson(url) {
  const response = await fetch(url);
  if (!response.ok) throw new Error(`HTTP ${response.status}`);
  return response.json();
}

function loadRun(path) {
  if (!runFiles.has(path)) runFiles.set(path, fetchJson(ResultsDir + path));
  return runFiles.get(path);
}

// # The table

async function render() {
  const count = ++renderCount;
  setStatus('Loading…');
  let run, vsRun;
  try {
    [run, vsRun] = await Promise.all([loadRun(runSelect.value), vsSelect.value ? loadRun(vsSelect.value) : null]);
  } catch (error) {
    setStatus(`Couldn't load a run file (${error.message}).`);
    return;
  }
  // A later selection may have rendered meanwhile
  if (count !== renderCount) return;

  setStatus('');
  destroyCharts(tableBody);
  summary.textContent = describeRun(run);
  tableBody.replaceChildren(...rowsOf(run).map(row => tableRow(row, run, vsRun)));
}

function describeRun(run) {
  const { app, suite, config } = run;
  const dirty = flag => flag === true ? ' (dirty)' : '';
  return `Run of ${formatDate(run.startTime)} on ${run.machine.name}: app ${app.version} ${app.commit.slice(0, 8)}` +
    `${dirty(app.dirty)}, measured by the suite at ${(suite.commit ?? 'unknown').slice(0, 8)}${dirty(suite.dirty)}; ` +
    `${plural(config.launches, 'launch', 'launches')} of ${config.warmupIterations} warm-up and ` +
    `${config.timedIterations} timed iterations, paced every ${config.paceMillis} ms.`;
}

/** One row per metric of the run, the floor first, or one per failed case. */
function rowsOf(run) {
  const outcomes = [{ caseName: 'floor', outcome: run.floor }, ...run.cases.map(c => ({ caseName: c.name, outcome: c }))];
  return outcomes.flatMap(({ caseName, outcome }) => outcome.outcome === 'passed'
    ? outcome.metrics.map(metric => ({ caseName, metric }))
    : [{ caseName, failure: outcome.failure }]);
}

function metricOf(run, caseName, metricName) {
  const outcome = caseName === 'floor' ? run?.floor : run?.cases.find(c => c.name === caseName);
  return outcome?.outcome === 'passed' ? outcome.metrics.find(metric => metric.name === metricName) : undefined;
}

function tableRow(row, run, vsRun) {
  if (row.failure !== undefined) {
    return element('tr', { className: 'failed' }, [
      element('td', { className: 'case', text: row.caseName }),
      element('td', { className: 'failure', colSpan: 6, text: `Failed: ${row.failure}` })
    ]);
  }

  const { metric } = row;
  const vsMetric = metricOf(vsRun, row.caseName, metric.name);
  const result = vsRun ? drift(vsMetric, metric) : undefined;
  const tr = element('tr', { className: 'metric', tabIndex: 0 }, [
    element('td', { className: 'case', text: row.caseName }),
    element('td', { text: metric.name }),
    element('td', { className: 'number', text: formatNs(metric.median) }),
    element('td', { className: 'number', text: formatNs(metric.p99) }),
    element('td', { className: 'number', text: formatNs(metric.max) }),
    element('td', { className: 'number', text: vsMetric ? formatChange(metric.median, vsMetric.median) : '—' }),
    element('td', { className: 'drift', text: driftLabel(result) })
  ]);
  tr.classList.toggle('drifted', result?.status === 'significant');
  tr.setAttribute('aria-expanded', 'false');

  const toggle = () => toggleDetails(tr, row, run, vsRun, vsMetric, result);
  tr.addEventListener('click', toggle);
  tr.addEventListener('keydown', event => {
    if (event.key === 'Enter' || event.key === ' ') {
      event.preventDefault();
      toggle();
    }
  });
  return tr;
}

function formatChange(value, vsValue) {
  const change = value - vsValue;
  const sign = change > 0 ? '+' : change < 0 ? '−' : '';
  const percent = vsValue ? ` (${sign}${Math.abs(change / vsValue * 100).toFixed(1)}%)` : '';
  return `${sign}${formatNs(Math.abs(change))}${percent}`;
}

function driftLabel(result) {
  if (!result) return '—';
  return { new: 'new', significant: 'drifted', 'not-significant': 'not significant' }[result.status];
}

// # The details of a metric

function toggleDetails(tr, row, run, vsRun, vsMetric, result) {
  const next = tr.nextElementSibling;
  if (next?.classList.contains('details')) {
    destroyCharts(next);
    next.remove();
    tr.setAttribute('aria-expanded', 'false');
    return;
  }

  const cell = element('td', { colSpan: 7 });
  tr.after(element('tr', { className: 'details' }, [cell]));
  tr.setAttribute('aria-expanded', 'true');

  const timeline = element('section', {}, [element('h2', { text: 'Timeline across the machine\'s runs' })]);
  const distribution = element('section', {}, [element('h2', { text: 'Distribution' })]);
  cell.append(
    element('div', { className: 'panels' }, [timeline, distribution]),
    element('div', { className: 'panels' }, [
      statisticsSection(row.metric, vsMetric),
      launchesSection(row.metric),
      driftSection(result, vsMetric),
      metadataSection(run)
    ])
  );
  renderDistribution(distribution, row.metric, vsMetric, vsRun);
  renderTimeline(timeline, row).catch(error =>
    timeline.append(element('p', { className: 'note', text: `Couldn't load the runs (${error.message}).` })));
}

function statisticsSection(metric, vsMetric) {
  const keys = [['count', 'Count'], ['min', 'Min'], ['mean', 'Mean'], ['stdDev', 'Std. dev.'], ['median', 'Median'],
    ['p90', 'p90'], ['p95', 'p95'], ['p99', 'p99'], ['max', 'Max']];
  const value = (m, key) => m === undefined ? '—' : key === 'count' ? String(m[key]) : formatNs(m[key]);
  return element('section', {}, [
    element('h2', { text: 'Statistics' }),
    element('table', {}, [
      element('thead', {}, [element('tr', {}, [
        element('th', { text: '' }),
        element('th', { className: 'number', text: 'This run' }),
        element('th', { className: 'number', text: 'vs' })
      ])]),
      element('tbody', {}, keys.map(([key, label]) => element('tr', {}, [
        element('th', { text: label }),
        element('td', { className: 'number', text: value(metric, key) }),
        element('td', { className: 'number', text: value(vsMetric, key) })
      ])))
    ])
  ]);
}

function launchesSection(metric) {
  return element('section', {}, [
    element('h2', { text: 'Launches' }),
    element('table', {}, [
      element('thead', {}, [element('tr', {}, ['Launch', 'Count', 'Median', 'p99', 'Max'].map(label =>
        element('th', { className: label === 'Launch' ? '' : 'number', text: label })))]),
      element('tbody', {}, metric.launches.map((launch, i) => element('tr', {}, [
        element('td', { text: String(i + 1) }),
        element('td', { className: 'number', text: String(launch.count) }),
        element('td', { className: 'number', text: formatNs(launch.median) }),
        element('td', { className: 'number', text: formatNs(launch.p99) }),
        element('td', { className: 'number', text: formatNs(launch.max) })
      ])))
    ]),
    element('p', { className: 'note', text: 'Launches that disagree mean that the JVM, not the app, moved the numbers.' })
  ]);
}

function driftSection(result, vsMetric) {
  const section = element('section', {}, [element('h2', { text: 'Drift' })]);
  if (!result) {
    section.append(element('p', { className: 'note', text: 'Pick a "vs" run to compare with.' }));
  } else if (result.status === 'new') {
    section.append(element('p', { text: 'The "vs" run has no such metric.' }));
  } else {
    const minimum = value => formatNs(Math.max(MIN_CHANGE_RATIO * value, MIN_CHANGE_NS));
    section.append(element('dl', {}, [
      ['Verdict', result.status === 'significant' ? 'Drifted' : 'Not significant'],
      ['Median change', `${formatChange(vsMetric.median + result.medianChange, vsMetric.median)}, minimum ` +
        `${minimum(vsMetric.median)}`],
      ['Mann–Whitney U', `U = ${result.mw.u.toFixed(0)}, z = ${result.mw.z.toFixed(2)}, p = ` +
        `${result.mw.p.toPrecision(3)} (significant below ${ALPHA})`],
      ['p99 change', `${formatChange(vsMetric.p99 + result.p99Change, vsMetric.p99)}, minimum ${minimum(vsMetric.p99)}`],
      ['p99 change, 99% interval', `${formatSigned(result.p99Ci.low)} to ${formatSigned(result.p99Ci.high)}`]
    ].flatMap(([term, description]) => [element('dt', { text: term }), element('dd', { text: description })])));
    section.append(element('p', {
      className: 'note',
      text: 'Samples of one launch aren\'t fully independent, which makes the tests somewhat optimistic.'
    }));
  }
  return section;
}

function formatSigned(ns) {
  return `${ns < 0 ? '−' : ns > 0 ? '+' : ''}${formatNs(Math.abs(ns))}`;
}

function metadataSection(run) {
  const { machine, jvm, app, suite, config } = run;
  const commit = (hash, dirty) => `${hash ?? 'unknown'}${dirty === true ? ' (dirty)' : dirty === null ? ' (dirty unknown)' : ''}`;
  return element('section', {}, [
    element('h2', { text: 'Run' }),
    element('dl', {}, [
      ['Machine', machine.name === `${machine.model} · ${machine.cpu}`
        ? machine.name : `${machine.name} (${machine.model}, ${machine.cpu})`],
      ['Cores', `${machine.physicalCores} physical, ${machine.logicalCores} logical`],
      ['Memory', `${(machine.memoryBytes / 2 ** 30).toFixed(0)} GiB`],
      ['OS', `${machine.os.name} ${machine.os.version}`],
      ['Power', `${machine.powerSource ?? 'unknown'}${machine.lowPowerMode ? ', Low Power Mode' : ''}`],
      ['MIDI devices', `${machine.midiDevices.input.name} → ${machine.midiDevices.output.name}`],
      ['JDK', `${jvm.vendor} ${jvm.version}${jvm.options.length ? ` ${jvm.options.join(' ')}` : ''}`],
      ['Scala', app.scalaVersion],
      ['App', `${app.version} at ${commit(app.commit, app.dirty)}`],
      ['Suite', commit(suite.commit, suite.dirty)],
      ['Measured', `${plural(config.launches, 'launch', 'launches')}, ${config.warmupIterations} warm-up and ` +
        `${config.timedIterations} timed iterations, paced every ${config.paceMillis} ms`],
      ['Time', `${formatDate(run.startTime)} to ${formatDate(run.endTime)}`]
    ].flatMap(([term, description]) => [element('dt', { text: term }), element('dd', { text: description })]))
  ]);
}

// # Charts

function chartLibrary(section) {
  if (window.Chart) return window.Chart;
  section.append(element('p', { className: 'note', text: 'The charts need Chart.js, loaded from the network.' }));
  return undefined;
}

function cssColor(name) {
  return getComputedStyle(document.documentElement).getPropertyValue(name).trim();
}

function chartCanvas(section) {
  const canvas = element('canvas');
  section.append(element('div', { className: 'chart' }, [canvas]));
  return canvas;
}

function axisColors() {
  return { color: cssColor('--muted'), grid: cssColor('--border') };
}

async function renderTimeline(section, row) {
  const Chart = chartLibrary(section);
  if (!Chart) return;
  const entries = machineRuns();
  const runs = await Promise.all(entries.map(entry => loadRun(entry.path)));
  const points = runs.map(run => metricOf(run, row.caseName, row.metric.name));
  const { color, grid } = axisColors();

  const toggles = element('div', { className: 'toggles' });
  section.append(toggles);
  const chart = new Chart(chartCanvas(section), {
    type: 'line',
    data: {
      labels: entries.map(entry => `${entry.appVersion} ${entry.appCommit.slice(0, 8)}`),
      datasets: Statistics.map((statistic, i) => ({
        label: statistic.label,
        data: points.map(metric => metric ? metric[statistic.key] / 1000 : null),
        hidden: !statistic.shown,
        borderColor: cssColor(`--series-${i + 1}`),
        backgroundColor: cssColor(`--series-${i + 1}`),
        spanGaps: true
      }))
    },
    options: {
      maintainAspectRatio: false,
      animation: false,
      interaction: { mode: 'index', intersect: false },
      plugins: {
        legend: { display: false },
        tooltip: {
          callbacks: {
            title: items => `${items[0].label} · ${formatDate(entries[items[0].dataIndex].startTime)}`,
            label: item => `${item.dataset.label}: ${formatNs(item.parsed.y * 1000)}`
          }
        }
      },
      scales: {
        x: { ticks: { color }, grid: { color: grid } },
        y: { title: { display: true, text: 'µs', color }, ticks: { color }, grid: { color: grid } }
      }
    }
  });
  toggles.append(...Statistics.map((statistic, i) => {
    const checkbox = element('input', { type: 'checkbox', checked: Boolean(statistic.shown) });
    checkbox.addEventListener('change', () => {
      chart.data.datasets[i].hidden = !checkbox.checked;
      chart.update();
    });
    return element('label', {}, [checkbox, statistic.label]);
  }));
}

/** About how many bins a distribution chart shows: the 1%-wide bins of a run file are merged into wider ones. */
const DisplayBins = 60;

/** How many bins of the run files a bin of the chart merges, so that the metrics' range spans about DisplayBins. */
function displayFactor(metrics) {
  const lowers = metrics.flatMap(metric => metric.histogram.bins.map(([lower]) => Math.max(lower, 1)));
  const span = Math.log(Math.max(...lowers) / Math.min(...lowers)) / Math.log(metrics[0].histogram.growth);
  return Math.max(1, Math.ceil(span / DisplayBins));
}

/**
 * The outline of a histogram, in µs, with `factor` of its bins merged into one: each merged bin's share of the samples
 * over its span, and zero over the empty ones, which the run file leaves out.
 */
function histogramOutline(metric, factor) {
  const { growth, bins } = metric.histogram;
  const width = growth ** factor;
  const counts = new Map();
  let total = 0;
  for (const [lower, count] of bins) {
    const index = Math.floor(Math.round(Math.log(Math.max(lower, 1)) / Math.log(growth)) / factor);
    counts.set(index, (counts.get(index) ?? 0) + count);
    total += count;
  }

  const merged = [...counts].sort((a, b) => a[0] - b[0]);
  const points = [];
  merged.forEach(([index, count], i) => {
    const lower = width ** index / 1000, upper = lower * width;
    if (i === 0 || merged[i - 1][0] !== index - 1) points.push({ x: lower, y: 0 });
    points.push({ x: lower, y: count / total }, { x: upper, y: count / total });
    if (i === merged.length - 1 || merged[i + 1][0] !== index + 1) points.push({ x: upper, y: 0 });
  });
  return points;
}

function renderDistribution(section, metric, vsMetric, vsRun) {
  const Chart = chartLibrary(section);
  if (!Chart) return;
  const { color, grid } = axisColors();
  const factor = displayFactor(vsMetric ? [metric, vsMetric] : [metric]);
  const binPercent = ((metric.histogram.growth ** factor - 1) * 100).toFixed(0);
  const points = histogramOutline(metric, factor);
  const vsPoints = vsMetric ? histogramOutline(vsMetric, factor) : [];
  const top = Math.max(...points.map(p => p.y), ...vsPoints.map(p => p.y));
  const marker = (key, i) => ({
    label: `${key} ${formatNs(metric[key])}`,
    data: [{ x: metric[key] / 1000, y: 0 }, { x: metric[key] / 1000, y: top }],
    borderColor: cssColor(`--series-${i}`),
    borderDash: [2, 3],
    pointRadius: 0,
    showLine: true
  });

  const datasets = [{
    label: 'This run',
    data: points,
    borderColor: cssColor('--series-1'),
    backgroundColor: cssColor('--series-1'),
    pointRadius: 0,
    showLine: true
  }];
  if (vsMetric) {
    datasets.push({
      label: `vs ${vsRun.app.version} ${vsRun.app.commit.slice(0, 8)}`,
      data: vsPoints,
      borderColor: cssColor('--series-2'),
      backgroundColor: cssColor('--series-2'),
      borderDash: [6, 4],
      pointRadius: 0,
      showLine: true
    });
  }
  datasets.push(marker('median', 3), marker('p90', 4), marker('p99', 7));

  new Chart(chartCanvas(section), {
    type: 'scatter',
    data: { datasets },
    options: {
      maintainAspectRatio: false,
      animation: false,
      plugins: {
        legend: { labels: { color, boxHeight: 2 } },
        tooltip: { callbacks: { label: item => `${formatNs(item.parsed.x * 1000)}: ${(item.parsed.y * 100).toFixed(2)}%` } }
      },
      scales: {
        x: { type: 'logarithmic', title: { display: true, text: 'µs (log)', color }, ticks: { color }, grid: { color: grid } },
        y: {
          beginAtZero: true,
          title: { display: true, text: `share of samples per ${binPercent}% bin`, color },
          ticks: { color, callback: value => `${(value * 100).toFixed(1)}%` },
          grid: { color: grid }
        }
      }
    }
  });
}

function destroyCharts(container) {
  if (!window.Chart) return;
  for (const canvas of container.querySelectorAll('canvas')) window.Chart.getChart(canvas)?.destroy();
}

// # Helpers

function setStatus(text) {
  statusLine.textContent = text;
}

function plural(count, one, many) {
  return `${count} ${count === 1 ? one : many}`;
}

function formatDate(iso) {
  return `${new Date(iso).toISOString().slice(0, 16).replace('T', ' ')} UTC`;
}

/** Builds an element with the given properties (`text` sets its text) and children (elements or strings). */
function element(tag, properties = {}, children = []) {
  const node = document.createElement(tag);
  for (const [name, value] of Object.entries(properties)) {
    if (name === 'text') node.textContent = value;
    else node[name] = value;
  }
  node.append(...children);
  return node;
}
