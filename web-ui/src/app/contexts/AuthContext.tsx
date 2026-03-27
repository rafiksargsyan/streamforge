import { createContext, useState, useEffect, type ReactNode } from 'react';
import {
  GoogleAuthProvider,
  GithubAuthProvider,
  signInWithPopup,
  sendSignInLinkToEmail,
  isSignInWithEmailLink,
  signInWithEmailLink,
  onAuthStateChanged,
  signOut as firebaseSignOut,
  type User,
} from 'firebase/auth';
import { auth } from '../../lib/firebase';
import { signUpExternal } from '../../api/users';
import { saveEmailForSignIn, getEmailForSignIn, clearEmailForSignIn } from '../../utils/emailStorage';
import type { AuthContextValue } from '../../types/auth.types';

export const AuthContext = createContext<AuthContextValue>({
  user: null,
  userId: null,
  accountId: null,
  loading: true,
  pendingEmailConfirmation: false,
  signInWithGoogle: async () => {},
  signInWithGithub: async () => {},
  sendMagicLink: async () => {},
  confirmEmailForLink: async () => {},
  signOut: async () => {},
});

export function AuthProvider({ children }: { children: ReactNode }) {
  const [user, setUser] = useState<User | null>(null);
  const [userId, setUserId] = useState<string | null>(null);
  const [accountId, setAccountId] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const [pendingEmailConfirmation, setPendingEmailConfirmation] = useState(false);

  useEffect(() => {
    if (isSignInWithEmailLink(auth, window.location.href)) {
      const email = getEmailForSignIn();
      if (email) {
        signInWithEmailLink(auth, email, window.location.href)
          .then(() => {
            clearEmailForSignIn();
            window.history.replaceState({}, document.title, '/');
          })
          .catch(console.error);
      } else {
        setPendingEmailConfirmation(true);
      }
    }

    const unsubscribe = onAuthStateChanged(auth, async (firebaseUser) => {
      if (firebaseUser) {
        try {
          const dto = await signUpExternal(firebaseUser);
          setUserId(dto.id);
          setAccountId(dto.accountId);
        } catch (err) {
          console.error('signup-external failed', err);
        }
      } else {
        setUserId(null);
        setAccountId(null);
      }
      setUser(firebaseUser);
      setLoading(false);
    });

    return unsubscribe;
  }, []);

  const signInWithGoogle = async () => {
    await signInWithPopup(auth, new GoogleAuthProvider());
  };

  const signInWithGithub = async () => {
    await signInWithPopup(auth, new GithubAuthProvider());
  };

  const sendMagicLink = async (email: string) => {
    const actionCodeSettings = {
      url: window.location.origin,
      handleCodeInApp: true,
    };
    await sendSignInLinkToEmail(auth, email, actionCodeSettings);
    saveEmailForSignIn(email);
  };

  const confirmEmailForLink = async (email: string) => {
    await signInWithEmailLink(auth, email, window.location.href);
    clearEmailForSignIn();
    setPendingEmailConfirmation(false);
    window.history.replaceState({}, document.title, '/');
  };

  const signOut = async () => {
    await firebaseSignOut(auth);
  };

  return (
    <AuthContext.Provider
      value={{
        user,
        userId,
        accountId,
        loading,
        pendingEmailConfirmation,
        signInWithGoogle,
        signInWithGithub,
        sendMagicLink,
        confirmEmailForLink,
        signOut,
      }}
    >
      {children}
    </AuthContext.Provider>
  );
}
