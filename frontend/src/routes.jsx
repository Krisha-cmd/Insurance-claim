import { lazy, Suspense } from 'react';
import { Navigate } from 'react-router-dom';
import Spinner from './components/Spinner.jsx';
import ProtectedRoute from './auth/ProtectedRoute.jsx';
import PolicyholderLayout from './layouts/PolicyholderLayout.jsx';
import AdjusterLayout from './layouts/AdjusterLayout.jsx';
import LoginPage from './pages/login/LoginPage.jsx';
import OverviewPage from './pages/policyholder/OverviewPage.jsx';
import PolicyPage from './pages/policyholder/PolicyPage.jsx';
import FileClaimPage from './pages/policyholder/FileClaimPage.jsx';
import MyClaimsPage from './pages/policyholder/MyClaimsPage.jsx';
import ClaimDetailPage from './pages/policyholder/ClaimDetailPage.jsx';
import QueuePage from './pages/adjuster/QueuePage.jsx';
import AllClaimsPage from './pages/adjuster/AllClaimsPage.jsx';
import ClaimReviewPage from './pages/adjuster/review/ClaimReviewPage.jsx';
import AuditLogPage from './pages/adjuster/AuditLogPage.jsx';
import ChaosPage from './pages/adjuster/ChaosPage.jsx';
import NotFoundPage from './pages/NotFoundPage.jsx';
import RootRedirect from './pages/RootRedirect.jsx';

// Recharts is heavy; load the analytics page (and its charts) on demand.
const AnalyticsPage = lazy(() => import('./pages/adjuster/AnalyticsPage.jsx'));

export const routes = [
  { path: '/', element: <RootRedirect /> },
  { path: '/login', element: <LoginPage /> },
  {
    element: <ProtectedRoute role="POLICYHOLDER" />,
    children: [{
      path: '/portal',
      element: <PolicyholderLayout />,
      children: [
        { index: true, element: <OverviewPage /> },
        { path: 'policy', element: <PolicyPage /> },
        { path: 'claims', element: <MyClaimsPage /> },
        { path: 'claims/new', element: <FileClaimPage /> },
        { path: 'claims/:id', element: <ClaimDetailPage /> },
      ],
    }],
  },
  {
    element: <ProtectedRoute role="ADJUSTER" />,
    children: [{
      path: '/ops',
      element: <AdjusterLayout />,
      children: [
        { index: true, element: <QueuePage /> },
        { path: 'claims', element: <AllClaimsPage /> },
        { path: 'claims/:id', element: <ClaimReviewPage /> },
        { path: 'analytics', element: <Suspense fallback={<Spinner />}><AnalyticsPage /></Suspense> },
        { path: 'audit', element: <AuditLogPage /> },
        { path: 'chaos', element: <ChaosPage /> },
      ],
    }],
  },
  { path: '/404', element: <NotFoundPage /> },
  { path: '*', element: <Navigate to="/404" replace /> },
];
