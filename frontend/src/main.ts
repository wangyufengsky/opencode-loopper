import { createApp } from 'vue'
import { ElButton, ElButtonGroup, ElDialog, ElForm, ElFormItem, ElInput, ElInputNumber, ElOption, ElSelect, ElSwitch, ElTabPane, ElTable, ElTableColumn, ElTabs } from 'element-plus'
import App from '@/App.vue'
import { router } from '@/router'
import { createPinia } from 'pinia'
import '@/styles/tokens.css'
import '@/styles/app.css'
import 'element-plus/es/components/button/style/css'
import 'element-plus/es/components/button-group/style/css'
import 'element-plus/es/components/dialog/style/css'
import 'element-plus/es/components/form/style/css'
import 'element-plus/es/components/form-item/style/css'
import 'element-plus/es/components/input/style/css'
import 'element-plus/es/components/input-number/style/css'
import 'element-plus/es/components/option/style/css'
import 'element-plus/es/components/select/style/css'
import 'element-plus/es/components/switch/style/css'
import 'element-plus/es/components/table/style/css'
import 'element-plus/es/components/table-column/style/css'
import 'element-plus/es/components/tabs/style/css'
import 'element-plus/es/components/tab-pane/style/css'
import 'element-plus/es/components/message/style/css'
import 'element-plus/es/components/message-box/style/css'

const app = createApp(App)
app.use(createPinia()).use(router)
for (const component of [ElButton, ElButtonGroup, ElDialog, ElForm, ElFormItem, ElInput, ElInputNumber, ElOption, ElSelect, ElSwitch, ElTabPane, ElTable, ElTableColumn, ElTabs]) app.use(component)
app.mount('#app')
