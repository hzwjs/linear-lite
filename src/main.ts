import { createApp } from "vue";
import { createPinia } from "pinia";
import "./style.css";
import AdaptiveRoot from "./AdaptiveRoot.vue";
import router, { setupRouterGuards } from "./router";
import { i18n } from "./i18n";
import { useLocaleStore } from "./store/localeStore";

const app = createApp(AdaptiveRoot);
const pinia = createPinia();

app.use(pinia);
useLocaleStore();
app.use(i18n);
app.use(router);
setupRouterGuards(pinia);

app.mount("#app");
