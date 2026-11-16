// Runtime configuration. Overwritten per environment at deploy time
// (e.g. mounted into the nginx container). Leave values empty to fall back
// to build-time VITE_* variables and then to the defaults in src/config/api.js.
window.__APP_CONFIG__ = {
  // API_BASE_URL: "https://api.example.com",
  // USE_MOCKS: "false",
};
