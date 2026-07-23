import { Navigate } from "react-router-dom";
import { useSelection } from "../context/SelectionContext";

const RequireSetup = ({ children }) => {
  const { isValidSelection, keysComplete } = useSelection();
  if (!isValidSelection || !keysComplete) {
    return <Navigate to="/select" replace />;
  }
  return children;
};

export default RequireSetup;
