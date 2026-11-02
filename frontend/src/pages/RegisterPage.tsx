import * as React from "react";
import { Eye, EyeOff, Lock, User } from "lucide-react";
import { Link, useNavigate } from "react-router-dom";

import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { useAuthStore } from "@/stores/authStore";

export function RegisterPage() {
  const navigate = useNavigate();
  const { register, isLoading } = useAuthStore();
  const [showPassword, setShowPassword] = React.useState(false);
  const [form, setForm] = React.useState({ username: "", password: "", confirmPassword: "" });
  const [error, setError] = React.useState<string | null>(null);

  const handleSubmit = async (event: React.FormEvent) => {
    event.preventDefault();
    setError(null);
    const username = form.username.trim();
    if (!username || !form.password) {
      setError("请输入用户名和密码。");
      return;
    }
    if (username.length < 3 || username.length > 64) {
      setError("用户名长度需为 3 到 64 个字符。");
      return;
    }
    if (form.password.length < 8) {
      setError("密码至少需要 8 个字符。");
      return;
    }
    if (form.password !== form.confirmPassword) {
      setError("两次输入的密码不一致。");
      return;
    }
    try {
      await register(username, form.password);
      navigate("/chat");
    } catch (err) {
      setError((err as Error).message || "注册失败，请稍后重试。");
    }
  };

  return (
    <main className="auth-screen">
      <section className="auth-panel">
        <div className="mb-6">
          <h1 className="font-display font-semibold">创建星序账号</h1>
          <p className="mt-1 text-sm text-muted-foreground">
            注册后即可登录，账号默认拥有普通用户权限。
          </p>
        </div>
        <form className="space-y-4" onSubmit={handleSubmit}>
          <div className="space-y-2">
            <label htmlFor="register-username" className="text-sm font-medium text-muted-foreground">
              用户名
            </label>
            <div className="relative">
              <User className="absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-muted-foreground" />
              <Input
                id="register-username"
                placeholder="3 到 64 个字符"
                value={form.username}
                onChange={(event) => setForm((prev) => ({ ...prev, username: event.target.value }))}
                className="pl-10"
                autoComplete="username"
                maxLength={64}
              />
            </div>
          </div>
          <div className="space-y-2">
            <label htmlFor="register-password" className="text-sm font-medium text-muted-foreground">
              密码
            </label>
            <div className="relative">
              <Lock className="absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-muted-foreground" />
              <Input
                id="register-password"
                type={showPassword ? "text" : "password"}
                placeholder="至少 8 个字符"
                value={form.password}
                onChange={(event) => setForm((prev) => ({ ...prev, password: event.target.value }))}
                className="pl-10 pr-10"
                autoComplete="new-password"
              />
              <button
                type="button"
                onClick={() => setShowPassword((prev) => !prev)}
                className="absolute right-3 top-1/2 -translate-y-1/2 text-muted-foreground"
                aria-label="显示或隐藏密码"
              >
                {showPassword ? <EyeOff className="h-4 w-4" /> : <Eye className="h-4 w-4" />}
              </button>
            </div>
          </div>
          <div className="space-y-2">
            <label htmlFor="register-confirm-password" className="text-sm font-medium text-muted-foreground">
              确认密码
            </label>
            <Input
              id="register-confirm-password"
              type={showPassword ? "text" : "password"}
              placeholder="再次输入密码"
              value={form.confirmPassword}
              onChange={(event) => setForm((prev) => ({ ...prev, confirmPassword: event.target.value }))}
              autoComplete="new-password"
            />
          </div>
          {error ? <p className="text-sm text-destructive">{error}</p> : null}
          <Button type="submit" className="w-full" disabled={isLoading}>
            {isLoading ? "正在创建账号..." : "注册并登录"}
          </Button>
        </form>
        <p className="mt-5 text-center text-sm text-muted-foreground">
          已有账号？{" "}
          <Link to="/login" className="font-medium text-primary hover:underline">
            返回登录
          </Link>
        </p>
      </section>
    </main>
  );
}
