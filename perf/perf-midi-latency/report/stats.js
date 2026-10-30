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

// The statistics of the latency report: whether a metric drifted between two runs, from the histograms of the run files.
// Pure functions, tested by test.html.
//
// A histogram is { growth, bins: [[lower bound in ns, count], ...] }, its bins ascending; a bin holds the durations from
// its lower bound up to about its lower bound times `growth`. A metric is a metric of a run file: its statistics and its
// histogram.

/** The significance level of the drift tests. */
export const ALPHA = 0.01;
/** A change smaller than this fraction of the "vs" value isn't worth flagging... */
export const MIN_CHANGE_RATIO = 0.05;
/** ...nor one smaller than this, in nanoseconds. */
export const MIN_CHANGE_NS = 20000;
/** How many times the bootstrap resamples each histogram. */
export const BOOTSTRAP_RESAMPLES = 1000;

/** The cumulative distribution function of the standard normal distribution. */
export function normalCdf(z) {
  // Abramowitz and Stegun's 7.1.26 approximation of erf, accurate to 1.5e-7
  const x = Math.abs(z) / Math.SQRT2;
  const t = 1 / (1 + 0.3275911 * x);
  const polynomial = t * (0.254829592 + t * (-0.284496736 + t * (1.421413741 + t * (-1.453152027 + t * 1.061405429))));
  const erf = 1 - polynomial * Math.exp(-x * x);
  return z >= 0 ? (1 + erf) / 2 : (1 - erf) / 2;
}

/** A seeded pseudo-random generator of numbers in [0, 1), so that the bootstrap gives the same interval every time. */
export function mulberry32(seed) {
  let state = seed >>> 0;
  return () => {
    state = (state + 0x6D2B79F5) >>> 0;
    let t = state;
    t = Math.imul(t ^ (t >>> 15), t | 1);
    t ^= t + Math.imul(t ^ (t >>> 7), t | 61);
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}

/**
 * The Mann-Whitney U test of whether the distribution of B is shifted from that of A, two-sided, with the normal
 * approximation corrected for ties. Each bin counts as a group of tied values, so the test needs no assumption about the
 * shape of the long-tailed latency distributions.
 */
export function mannWhitney(histA, histB) {
  const counts = new Map();
  for (const [lower, count] of histA.bins) counts.set(lower, [count, 0]);
  for (const [lower, count] of histB.bins) counts.set(lower, [counts.get(lower)?.[0] ?? 0, count]);

  let nA = 0, nB = 0, rankSumA = 0, rank = 0, ties = 0;
  for (const lower of [...counts.keys()].sort((a, b) => a - b)) {
    const [countA, countB] = counts.get(lower);
    const tied = countA + countB;
    // Tied values share the average of their ranks
    rankSumA += countA * (rank + (tied + 1) / 2);
    ties += tied ** 3 - tied;
    rank += tied;
    nA += countA;
    nB += countB;
  }

  const n = nA + nB;
  const u = rankSumA - nA * (nA + 1) / 2;
  const variance = nA * nB / 12 * ((n + 1) - ties / (n * (n - 1)));
  if (!(variance > 0)) return { u, z: 0, p: 1 };
  const z = (u - nA * nB / 2) / Math.sqrt(variance);
  return { u, z, p: Math.min(1, 2 * (1 - normalCdf(Math.abs(z)))) };
}

/**
 * The 99% bootstrap confidence interval of p99(B) − p99(A), in nanoseconds, resampling each histogram's bins by their
 * counts, a bin's samples standing at its geometric middle.
 */
export function bootstrapP99Change(histA, histB, { resamples = BOOTSTRAP_RESAMPLES, seed = 1 } = {}) {
  const random = mulberry32(seed);
  const a = resampler(histA, random), b = resampler(histB, random);
  const changes = Float64Array.from({ length: resamples }, () => b() - a());
  changes.sort();
  return {
    low: changes[Math.floor(0.005 * resamples)],
    high: changes[Math.ceil(0.995 * resamples) - 1]
  };
}

/** A function that draws a resample of the histogram's size and returns its nearest-rank p99. */
function resampler(hist, random) {
  const values = hist.bins.map(([lower]) => lower * Math.sqrt(hist.growth));
  const cumulative = [];
  let total = 0;
  for (const [, count] of hist.bins) cumulative.push(total += count);
  const drawn = new Int32Array(values.length);
  const rank = Math.ceil(0.99 * total);

  return () => {
    drawn.fill(0);
    for (let i = 0; i < total; i++) {
      drawn[binarySearch(cumulative, random() * total)]++;
    }
    let seen = 0;
    for (let bin = 0; bin < values.length; bin++) {
      seen += drawn[bin];
      if (seen >= rank) return values[bin];
    }
    return values[values.length - 1];
  };
}

/** The index of the first cumulative count above `target`. */
function binarySearch(cumulative, target) {
  let low = 0, high = cumulative.length - 1;
  while (low < high) {
    const middle = (low + high) >> 1;
    if (cumulative[middle] > target) high = middle;
    else low = middle + 1;
  }
  return low;
}

/**
 * Whether `metric` drifted since `vsMetric`: `new` without a "vs" metric; `significant` when a test is significant and
 * its change exceeds the minimum worth caring about, the larger of MIN_CHANGE_RATIO of the "vs" value and MIN_CHANGE_NS.
 * Mann-Whitney U tests a shift of the whole distribution, judged by the change of the median, and the bootstrap a move
 * of the tail, judged by the change of p99.
 */
export function drift(vsMetric, metric) {
  if (!vsMetric || !metric) return { status: 'new' };

  const medianChange = metric.median - vsMetric.median;
  const p99Change = metric.p99 - vsMetric.p99;
  const mw = mannWhitney(vsMetric.histogram, metric.histogram);
  const p99Ci = bootstrapP99Change(vsMetric.histogram, metric.histogram);
  const minimum = value => Math.max(MIN_CHANGE_RATIO * value, MIN_CHANGE_NS);
  const medianDrift = mw.p < ALPHA && Math.abs(medianChange) > minimum(vsMetric.median);
  const p99Drift = (p99Ci.low > 0 || p99Ci.high < 0) && Math.abs(p99Change) > minimum(vsMetric.p99);

  return {
    status: medianDrift || p99Drift ? 'significant' : 'not-significant',
    medianChange, p99Change, mw, p99Ci, medianDrift, p99Drift
  };
}

/** A duration in nanoseconds, in the unit that suits it. */
export function formatNs(ns) {
  const magnitude = Math.abs(ns);
  if (magnitude < 1000) return `${Math.round(ns)} ns`;
  if (magnitude < 1e6) return `${(ns / 1000).toFixed(1)} µs`;
  return `${(ns / 1e6).toFixed(2)} ms`;
}
