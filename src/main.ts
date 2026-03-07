import { createApp } from "vue";
import { createPinia } from "pinia";
import "./style.css";
import App from "./App.vue";
import router, { setupRouterGuards } from "./router";

const app = createApp(App);
const pinia = createPinia();

app.use(pinia);
app.use(router);
setupRouterGuards(pinia);

app.mount("#app");
