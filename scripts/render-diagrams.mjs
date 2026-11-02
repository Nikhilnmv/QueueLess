// Renders every docs/diagrams/*.mmd to docs/diagrams/png/*.png and copies each source into its
// Mermaid block in docs/diagrams/README.md, so the page, the PNGs and the sources stay the same.
//
// One-time setup:  npm install --prefix .local/mermaid mermaid   (and npm ci in frontend/)
// Run:             node scripts/render-diagrams.mjs
import { createRequire } from 'node:module';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const diagrams = path.join(root, 'docs/diagrams');
const pngDir = path.join(diagrams, 'png');
const mermaidJs = path.join(root, '.local/mermaid/node_modules/mermaid/dist/mermaid.min.js');
if (!fs.existsSync(mermaidJs)) {
  console.error('Mermaid is missing. Run: npm install --prefix .local/mermaid mermaid');
  process.exit(1);
}
const { chromium } = createRequire(path.join(root, 'frontend/package.json'))('@playwright/test');
const files = fs.readdirSync(diagrams).filter((f) => f.endsWith('.mmd')).sort();

// 1. Copy each source into the README block that follows its "Source:" line.
const readmePath = path.join(diagrams, 'README.md');
let readme = fs.readFileSync(readmePath, 'utf8');
for (const file of files) {
  const source = fs.readFileSync(path.join(diagrams, file), 'utf8').trimEnd();
  // The block ends at the first line that is exactly ``` (the block may be empty).
  const block = new RegExp(`(Source: \\[\`${file}\`\\]\\(${file}\\)[^\\n]*\\n\\n\`\`\`mermaid\\n)[\\s\\S]*?(^\`\`\`$)`, 'm');
  if (!block.test(readme)) console.warn(`README has no block for ${file}`);
  readme = readme.replace(block, (_, start, end) => `${start}${source}\n${end}`);
}
fs.writeFileSync(readmePath, readme);

// 2. Render the PNGs.
fs.mkdirSync(pngDir, { recursive: true });
const browser = await chromium.launch();
const page = await browser.newPage({ viewport: { width: 2400, height: 1600 }, deviceScaleFactor: 2 });
await page.setContent('<html><body style="margin:0;padding:16px;background:#fff"><div id="out" style="display:inline-block"></div></body></html>');
await page.addScriptTag({ content: fs.readFileSync(mermaidJs, 'utf8') });
await page.evaluate(() => window.mermaid.initialize({
  startOnLoad: false, theme: 'neutral', securityLevel: 'strict', fontFamily: 'Helvetica, Arial, sans-serif',
}));

let failed = 0;
for (const file of files) {
  const source = fs.readFileSync(path.join(diagrams, file), 'utf8');
  const result = await page.evaluate(async ({ source, id }) => {
    try {
      const { svg } = await window.mermaid.render(id, source);
      document.getElementById('out').innerHTML = svg;
      // Mermaid makes the SVG fluid (width 100%); give it its natural size instead.
      const element = document.querySelector('#out svg');
      const box = element.viewBox.baseVal;
      element.setAttribute('width', String(box.width));
      element.setAttribute('height', String(box.height));
      element.style.maxWidth = 'none';
      return { ok: true };
    } catch (error) {
      return { ok: false, error: String(error?.message ?? error) };
    }
  }, { source, id: `d${file.slice(0, 2)}` });
  if (!result.ok) {
    failed++;
    console.log(`FAIL ${file}: ${result.error.split('\n').slice(0, 3).join(' | ')}`);
    continue;
  }
  await (await page.$('#out')).screenshot({ path: path.join(pngDir, file.replace(/\.mmd$/, '.png')) });
  console.log(`ok   ${file}`);
}
await browser.close();
process.exit(failed ? 1 : 0);
