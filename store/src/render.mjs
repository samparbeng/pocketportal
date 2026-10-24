// Renders the store graphics: node render.mjs <resvg module dir>
import fs from 'fs';
import path from 'path';
import { createRequire } from 'module';
import { fileURLToPath } from 'url';
const here = path.dirname(fileURLToPath(import.meta.url));
const { Resvg } = createRequire(path.join(process.argv[2], 'x.js'))('@resvg/resvg-js');
const phone = fs.readFileSync(path.join(here, 'phone.svgfrag'), 'utf8');
for (const [tpl, out, width] of [['icon.svg.tpl', 'icon-512.png', 512], ['feature.svg.tpl', 'feature-graphic-1024x500.png', 1024]]) {
  const svg = fs.readFileSync(path.join(here, tpl), 'utf8').replace('@PHONE@', phone);
  const png = new Resvg(svg, { fitTo: { mode: 'width', value: width }, font: { loadSystemFonts: true, defaultFontFamily: 'Segoe UI' } }).render().asPng();
  fs.writeFileSync(path.join(here, '..', 'graphics', out), png);
  console.log(out, png.length);
}
