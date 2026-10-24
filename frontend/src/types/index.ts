export interface User {
  userId: string;
  username?: string;
  role: string;
  token: string;
  avatar?: string;
}

export type CurrentUser = Omit<User, "token">;

export interface Session {
  id: string;
  title: string;
  lastTime?: string;
}

export interface SourceRef {
  index?: number;
  docId: string;
  docName?: string;
  sourceType?: string;
  fileType?: string | null;
  url?: string | null;
  excerpt?: string;
}
