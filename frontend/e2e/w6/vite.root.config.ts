/** Browser lifecycle fixture builds the actual App/bootstrap, outside production dist. */
import {defineConfig} from 'vite'
import react from '@vitejs/plugin-react'
import {fileURLToPath} from 'node:url'
import {skinStyles,skinBootstrap} from '../../src/themes/compile'
export default defineConfig({root:fileURLToPath(new URL('.',import.meta.url)),plugins:[react(),{name:'loopper-skins',transformIndexHtml(){return [{tag:'style',children:skinStyles(),injectTo:'head'},{tag:'script',children:skinBootstrap(),injectTo:'head'}]}}],resolve:{alias:{'@':fileURLToPath(new URL('../../src',import.meta.url))}},build:{outDir:'/tmp/loopper-w6-root-dist',emptyOutDir:true}})
