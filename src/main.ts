import { createApp } from "vue";
import { createPinia } from "pinia";
import "./style.css";
import AdaptiveRoot from "./AdaptiveRoot.vue";
import router, { setupRouterGuards } from "./router";
import { i18n } from "./i18n";
import { useLocaleStore } from "./store/localeStore";
import { useAuthStore } from "./store/authStore";

const app = createApp(AdaptiveRoot);
const pinia = createPinia();

app.use(pinia);
useLocaleStore();
// 恢复本地会话时为图片资源补发路径级 Cookie，避免升级后已有会话看不到正文图片。
void useAuthStore().ensureDocumentAssetSession();
app.use(i18n);
app.use(router);
setupRouterGuards(pinia);

app.mount("#app");
