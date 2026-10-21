import {copyFile, mkdir} from 'node:fs/promises';
import {fileURLToPath} from 'node:url';

const root=new URL('../',import.meta.url);
const output=new URL('src/main/resources/static/vendor/vue/',root);
await mkdir(output,{recursive:true});
await copyFile(new URL('node_modules/vue/dist/vue.esm-browser.prod.js',root),new URL('vue.esm-browser.prod.js',output));
await copyFile(new URL('node_modules/vue/LICENSE',root),new URL('LICENSE.txt',output));
console.log('Vue browser runtime copied to '+fileURLToPath(output));
