import { Link } from "react-router-dom";

import { Button } from "@/components/ui/button";

export function NotFoundPage() {
  return (
    <div className="auth-screen">
      <div className="auth-panel max-w-md text-center">
        <div className="auth-brand justify-center"><span aria-hidden="true">✳</span> STARLINE / 404</div>
        <h1 className="font-display text-2xl font-semibold">页面不存在</h1>
        <p className="mt-2 text-sm text-muted-foreground">你访问的页面不存在。</p>
        <Button asChild className="mt-6">
          <Link to="/chat">返回聊天</Link>
        </Button>
      </div>
    </div>
  );
}
