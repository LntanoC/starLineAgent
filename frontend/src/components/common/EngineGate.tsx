import * as React from "react";

import { Loading } from "@/components/common/Loading";
import { AgentChatPage } from "@/pages/AgentChatPage";
import { ChatPage } from "@/pages/ChatPage";
import { useEngineStore } from "@/stores/engineStore";

export function EngineGate() {
  const engineType = useEngineStore((state) => state.engineType);
  const initialize = useEngineStore((state) => state.initialize);

  React.useEffect(() => {
    initialize().catch(() => null);
  }, [initialize]);

  if (!engineType) {
    return (
      <div className="flex h-screen items-center justify-center bg-white">
        <Loading />
      </div>
    );
  }

  return engineType === "agent" ? <AgentChatPage /> : <ChatPage />;
}
