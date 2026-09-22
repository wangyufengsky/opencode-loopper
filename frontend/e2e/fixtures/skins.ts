import { createApp } from 'vue'
import ElementPlus from 'element-plus'
import 'element-plus/dist/index.css'
import '@/styles/tokens.css'
import '@/styles/app.css'
import { initializeSkin } from '@/themes/state'
import SkinsPreview from './SkinsPreview.vue'
initializeSkin()
createApp(SkinsPreview).use(ElementPlus).mount('#app')
