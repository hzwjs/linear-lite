import { createApp } from "vue";
import { createPinia } from "pinia";
import "./style.css";
import App from "./App.vue";
import router, { setupRouterGuards } from "./router";
import { i18n } from "./i18n";
import { useLocaleStore } from "./store/localeStore";
import { setVeauryOptions } from 'veaury';
import { createRoot } from 'react-dom/client';

setVeauryOptions({
  react: {
    createRoot
  }
});

const app = createApp(App);
const pinia = createPinia();

app.use(pinia);
useLocaleStore();
app.use(i18n);
app.use(router);
setupRouterGuards(pinia);

app.mount("#app");
