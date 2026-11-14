import { useState, type FormEvent } from "react";
import { useSearchParams, Link } from "react-router-dom";
import { KeyRound, CheckCircle2, ArrowRight } from "lucide-react";
import { api } from "../lib/api";
import { ErrorText, PageHeading } from "../components/ui";

export function ActivateAccount() {
  const [params] = useSearchParams();
  const token = params.get("token") || "";

  const [password, setPassword] = useState("");
  const [confirmPassword, setConfirmPassword] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<unknown>(null);
  const [success, setSuccess] = useState(false);
  const [userEmail, setUserEmail] = useState("");

  const handleSubmit = async (e: FormEvent) => {
    e.preventDefault();
    if (password !== confirmPassword) {
      setError(new Error("Passwords do not match."));
      return;
    }
    if (password.length < 8) {
      setError(new Error("Password must be at least 8 characters long."));
      return;
    }

    setBusy(true);
    setError(null);
    try {
      const res = await api.activateAccount(token, password);
      setSuccess(true);
      setUserEmail(res.email);
    } catch (err) {
      setError(err);
    } finally {
      setBusy(false);
    }
  };

  if (!token) {
    return (
      <div className="container page" style={{ maxWidth: "480px" }}>
        <PageHeading
          title="Invalid Activation Link"
          text="This account activation link is missing a valid token. Please check the email or link provided by your department administrator."
        />
        <Link to="/login" className="button button-primary">
          Return to Sign in
        </Link>
      </div>
    );
  }

  return (
    <div className="container page" style={{ maxWidth: "480px", margin: "40px auto" }}>
      <div
        style={{
          background: "var(--paper)",
          border: "1px solid var(--line)",
          borderRadius: "8px",
          padding: "32px",
          boxShadow: "0 4px 6px -1px rgba(0, 0, 0, 0.05)",
        }}
      >
        <div style={{ textAlign: "center", marginBottom: "24px" }}>
          <div
            style={{
              width: "48px",
              height: "48px",
              borderRadius: "50%",
              background: "#e0e7ff",
              color: "var(--accent)",
              display: "inline-flex",
              alignItems: "center",
              justifyContent: "center",
              marginBottom: "12px",
            }}
          >
            {success ? <CheckCircle2 size={24} color="#16a34a" /> : <KeyRound size={24} />}
          </div>
          <h2 style={{ fontSize: "20px", fontWeight: 700, margin: 0 }}>
            {success ? "Account Activated!" : "Activate Your QuickSlot Account"}
          </h2>
          <p style={{ fontSize: "13px", color: "var(--muted)", margin: "8px 0 0" }}>
            {success
              ? `Your account (${userEmail}) is verified and ready to use.`
              : "Set your permanent password to complete your account setup."}
          </p>
        </div>

        {!!error && <ErrorText error={error} />}

        {success ? (
          <div style={{ textAlign: "center", marginTop: "24px" }}>
            <Link
              to="/login"
              className="button button-primary"
              style={{ display: "inline-flex", alignItems: "center", gap: "8px", width: "100%", justifyContent: "center" }}
            >
              Sign In to Your Account <ArrowRight size={16} />
            </Link>
          </div>
        ) : (
          <form onSubmit={handleSubmit} style={{ display: "flex", flexDirection: "column", gap: "16px" }}>
            <div>
              <label style={{ display: "block", fontSize: "13px", fontWeight: 600, marginBottom: "4px" }}>
                New Password (min. 8 characters)
              </label>
              <input
                type="password"
                required
                minLength={8}
                value={password}
                onChange={(e) => setPassword(e.target.value)}
                placeholder="Choose a strong password"
                style={{ width: "100%", padding: "10px 12px", fontSize: "14px", border: "1px solid var(--line)", borderRadius: "6px" }}
              />
            </div>

            <div>
              <label style={{ display: "block", fontSize: "13px", fontWeight: 600, marginBottom: "4px" }}>
                Confirm Password
              </label>
              <input
                type="password"
                required
                minLength={8}
                value={confirmPassword}
                onChange={(e) => setConfirmPassword(e.target.value)}
                placeholder="Re-enter password"
                style={{ width: "100%", padding: "10px 12px", fontSize: "14px", border: "1px solid var(--line)", borderRadius: "6px" }}
              />
            </div>

            <button
              type="submit"
              className="button button-primary"
              disabled={busy}
              style={{ width: "100%", marginTop: "8px" }}
            >
              {busy ? "Activating Account…" : "Activate Account & Continue"}
            </button>
          </form>
        )}
      </div>
    </div>
  );
}
