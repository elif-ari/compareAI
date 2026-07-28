import { Navigate, useSearchParams } from "react-router-dom";
import { useSelection } from "../context/SelectionContext";

const RequireSetup = ({ children }) => {
  const { isValidSelection } = useSelection();
  const [searchParams] = useSearchParams();

  // Kaydedilmiş bir sohbet geçmişten açılıyorsa (?conversationId=...), o konuşmanın kendi
  // providers/mode bilgisi Chat.jsx tarafından yüklenip SelectionContext'e yazılacak - o yüzden
  // burada henüz geçerli bir seçim yoksa bile "Yeni Sohbet" ekranına yönlendirmiyoruz.
  const isOpeningSavedConversation = !!searchParams.get("conversationId");

  if (!isValidSelection && !isOpeningSavedConversation) {
    return <Navigate to="/new-chat" replace />;
  }
  return children;
};

export default RequireSetup;
