import { Navigate } from "react-router-dom";
import { useSelection } from "../context/SelectionContext";

const RequireSetup = ({ children }) => {
  const { isValidSelection } = useSelection();
  if (!isValidSelection) {
    return <Navigate to="/new-chat" replace />;
  }
  return children;
};

export default RequireSetup;
