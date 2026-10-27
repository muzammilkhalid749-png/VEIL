import { initializeApp, getApps, type FirebaseApp } from 'firebase/app';
import { getFirestore, type Firestore } from 'firebase/firestore';
import { getAuth, type Auth } from 'firebase/auth';

const firebaseConfig = {
  apiKey: import.meta.env.VITE_FIREBASE_API_KEY || "AIzaSyDummyKeyForDevelopmentVeilMesh001",
  authDomain: import.meta.env.VITE_FIREBASE_AUTH_DOMAIN || "veil-2b956.firebaseapp.com",
  projectId: import.meta.env.VITE_FIREBASE_PROJECT_ID || "veil-2b956",
  storageBucket: import.meta.env.VITE_FIREBASE_STORAGE_BUCKED || "veil-2b956.appspot.com",
  messagingSenderId: import.meta.env.VITE_FIREBASE_MESSAGING_SENDER_ID || "104857600000",
  appId: import.meta.env.VITE_FIREBASE_APP_ID || "1:104857600000:web:9876543210abcdef",
};

let app: FirebaseApp;
let db: Firestore | null = null;
let auth: Auth | null = null;

try {
  app = getApps().length === 0 ? initializeApp(firebaseConfig) : getApps()[0];
  db = getFirestore(app);
  auth = getAuth(app);
} catch (err) {
  console.warn('[VEIL Firebase] Firestore initialization deferred or using mock:', err);
}

export { app, db, auth, firebaseConfig };
