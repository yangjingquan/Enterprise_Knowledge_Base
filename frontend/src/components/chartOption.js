// 根据结果表 + 大模型的图表建议生成 ECharts option；不适合画图时返回 null
const COLORS = ['#6366f1', '#22c55e', '#f59e0b', '#ef4444', '#06b6d4', '#a855f7', '#ec4899', '#84cc16']

const isNum = v => typeof v === 'number' && Number.isFinite(v)

export function chartable(table) {
  return table && table.rows && table.rows.length >= 2 && table.columns.length >= 2
}

export function buildOption(table, chart) {
  if (!chartable(table)) return null
  const cols = table.columns
  const rows = table.rows.slice(0, 200)
  const numCols = cols.map((c, i) => i).filter(i => rows.every(r => r[i] === null || isNum(r[i])) && rows.some(r => isNum(r[i])))
  let type = chart?.type
  if (type === 'table') return null
  let xi = cols.indexOf(chart?.x)
  if (xi < 0) xi = cols.findIndex((c, i) => !numCols.includes(i))
  if (xi < 0) return null
  let ys = (Array.isArray(chart?.y) ? chart.y : chart?.y ? [chart.y] : []).map(y => cols.indexOf(y)).filter(i => i >= 0 && numCols.includes(i) && i !== xi)
  if (!ys.length) ys = numCols.filter(i => i !== xi).slice(0, 3)
  if (!ys.length) return null
  const xs = rows.map(r => String(r[xi] ?? ''))
  if (!['line', 'bar', 'pie'].includes(type)) type = /^\d{4}-\d{2}/.test(xs[0]) ? 'line' : 'bar'
  if (type === 'pie' && (rows.length > 12 || rows.some(r => (r[ys[0]] ?? 0) < 0))) type = 'bar'
  if (type === 'pie') {
    return {
      color: COLORS, tooltip: { trigger: 'item', valueFormatter: fmt },
      legend: { type: 'scroll', bottom: 0 },
      series: [{ type: 'pie', radius: ['35%', '65%'], name: cols[ys[0]], data: rows.map(r => ({ name: String(r[xi]), value: r[ys[0]] })) }],
    }
  }
  return {
    color: COLORS,
    tooltip: { trigger: 'axis', valueFormatter: fmt },
    legend: ys.length > 1 ? { top: 0 } : undefined,
    grid: { left: 12, right: 16, top: ys.length > 1 ? 32 : 16, bottom: rows.length > 30 ? 40 : 8, containLabel: true },
    xAxis: { type: 'category', data: xs, axisLabel: { hideOverlap: true } },
    yAxis: { type: 'value', axisLabel: { formatter: v => fmt(v) } },
    dataZoom: rows.length > 30 ? [{ type: 'slider', height: 16, bottom: 8 }] : undefined,
    series: ys.map(i => ({ type, name: cols[i], data: rows.map(r => r[i]), smooth: type === 'line', barMaxWidth: 36 })),
  }
}

function fmt(v) {
  if (!isNum(v)) return v
  const a = Math.abs(v)
  if (a >= 1e8) return (v / 1e8).toFixed(2).replace(/\.?0+$/, '') + '亿'
  if (a >= 1e4) return (v / 1e4).toFixed(2).replace(/\.?0+$/, '') + '万'
  return Number.isInteger(v) ? String(v) : v.toFixed(2)
}
