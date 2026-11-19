import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

// Local stand-in for the ALB: each /api prefix goes to the service that owns it, on the port
// backend/local/run-all.sh gives it. Used by `npm run dev:backend` (API_BASE_URL = this dev server).
// On EKS the ALB Ingress does this routing and the frontend calls its own origin.
const SERVICES = {
  '/api/auth': 8081,         // login
  '/api/policies': 8082,     // policy
  '/api/claims': 8083,       // claim-intake
  '/api/eligibility': 8084,  // eligibility
  '/api/assessments': 8085,  // assessment
  '/api/approvals': 8086,    // approval
  '/api/audit': 8089,        // ops-dashboard
  '/api/analytics': 8089,
  '/api/ops': 8089,
};

export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    strictPort: true, // the backend mode's API_BASE_URL points here, so the port must not drift
    proxy: Object.fromEntries(
      Object.entries(SERVICES).map(([prefix, port]) => [prefix, { target: `http://localhost:${port}`, changeOrigin: true }]),
    ),
  },
});
