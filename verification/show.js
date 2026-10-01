// Pretty-print a simulation/benchmark JSON summary from stdin.
let d = '';
process.stdin.on('data', (c) => (d += c)).on('end', () => {
  const i = d.indexOf('{\n');
  try {
    const j = JSON.parse(d.slice(i));
    const { audit, checks, ...rest } = j;
    console.log(JSON.stringify(rest, null, 1));
    if (audit) console.log('AUDIT', JSON.stringify(audit));
    for (const c of checks || []) console.log(c.pass ? 'PASS' : 'FAIL', c.name, '—', c.detail);
  } catch (e) {
    console.log(d.slice(-4000));
  }
});
