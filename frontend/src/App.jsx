import { Routes, Route, Navigate } from 'react-router-dom';
import { AuthProvider } from './context/AuthContext';
import { SelectionProvider } from './context/SelectionContext';
import RequireAuth from './components/RequireAuth';
import RequireSetup from './components/RequireSetup';
import Login from './pages/Login';
import SelectProviders from './pages/SelectProviders';
import ApiKeys from './pages/ApiKeys';
import Compare from './pages/Compare';

function App() {
  return (
    <AuthProvider>
      <SelectionProvider>
        <Routes>
          <Route path="/login" element={<Login />} />
          <Route
            path="/select"
            element={
              <RequireAuth>
                <SelectProviders />
              </RequireAuth>
            }
          />
          <Route
            path="/keys"
            element={
              <RequireAuth>
                <ApiKeys />
              </RequireAuth>
            }
          />
          <Route
            path="/compare"
            element={
              <RequireAuth>
                <RequireSetup>
                  <Compare />
                </RequireSetup>
              </RequireAuth>
            }
          />
          <Route path="*" element={<Navigate to="/login" replace />} />
        </Routes>
      </SelectionProvider>
    </AuthProvider>
  );
}

export default App;
