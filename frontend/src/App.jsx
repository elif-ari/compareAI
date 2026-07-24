import { Routes, Route, Navigate } from 'react-router-dom';
import { AuthProvider } from './context/AuthContext';
import { SelectionProvider } from './context/SelectionContext';
import RequireAuth from './components/RequireAuth';
import RequireSetup from './components/RequireSetup';
import Login from './pages/Login';
import Dashboard from './pages/Dashboard';
import NewChat from './pages/NewChat';
import Chat from './pages/Chat';

function App() {
  return (
    <AuthProvider>
      <SelectionProvider>
        <Routes>
          <Route path="/login" element={<Login />} />
          <Route
            path="/dashboard"
            element={
              <RequireAuth>
                <Dashboard />
              </RequireAuth>
            }
          />
          <Route
            path="/new-chat"
            element={
              <RequireAuth>
                <NewChat />
              </RequireAuth>
            }
          />
          <Route
            path="/chat"
            element={
              <RequireAuth>
                <RequireSetup>
                  <Chat />
                </RequireSetup>
              </RequireAuth>
            }
          />
          <Route path="*" element={<Navigate to="/dashboard" replace />} />
        </Routes>
      </SelectionProvider>
    </AuthProvider>
  );
}

export default App;
