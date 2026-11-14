import { Link, NavLink, Outlet, useNavigate } from "react-router-dom";
import { ArrowUpRight, Bell, CheckCheck, LogOut, Menu, X } from "lucide-react";
import { useEffect, useRef, useState } from "react";
import { canRequest, canReview, canViewFaculty, useAuth } from "../lib/auth";
import { roleName } from "../lib/format";
import { api } from "../lib/api";
import type { AppNotification } from "../lib/types";

export function Layout() {
  const { user, logout } = useAuth();
  const [menu, setMenu] = useState(false);
  const [error, setError] = useState("");
  const [notifications, setNotifications] = useState<AppNotification[]>([]);
  const [notifOpen, setNotifOpen] = useState(false);
  const notifRef = useRef<HTMLDivElement>(null);
  const navigate = useNavigate();

  useEffect(() => {
    if (!user) {
      setNotifications([]);
      return;
    }
    const loadNotifs = () => {
      api.notifications().then(setNotifications).catch(() => {});
    };
    loadNotifs();
    const interval = setInterval(loadNotifs, 15000);
    return () => clearInterval(interval);
  }, [user]);

  useEffect(() => {
    const handleClick = (e: MouseEvent) => {
      if (notifRef.current && !notifRef.current.contains(e.target as Node)) {
        setNotifOpen(false);
      }
    };
    document.addEventListener("mousedown", handleClick);
    return () => document.removeEventListener("mousedown", handleClick);
  }, []);

  const unreadCount = notifications.filter((n) => !n.is_read).length;

  const markRead = async (id: string, requestId?: string) => {
    try {
      await api.markNotificationRead(id);
      setNotifications((prev) =>
        prev.map((n) => (n.id === id ? { ...n, is_read: true } : n)),
      );
      if (requestId) {
        setNotifOpen(false);
        navigate(canReview(user?.role) ? "/approvals" : "/requests");
      }
    } catch {
      // ignore
    }
  };

  const markAllRead = async () => {
    try {
      await api.markAllNotificationsRead();
      setNotifications((prev) => prev.map((n) => ({ ...n, is_read: true })));
    } catch {
      // ignore
    }
  };

  const signOut = async () => {
    try {
      await logout();
      navigate("/");
    } catch (failure) {
      setError(failure instanceof Error ? failure.message : "Sign out failed");
    }
  };

  return (
    <>
      <a className="skip-link" href="#main">
        Skip to content
      </a>
      <header className="institution-header">
        <div className="header-shell container">
          <div className="institution-masthead">
            <a
              className="university-wordmark"
              href="https://gehu.ac.in/"
              target="_blank"
              rel="noreferrer"
              aria-label="Graphic Era Hill University website"
            >
              <img src="/brand/gehu-emblem.svg" alt="" width="46" height="46" />
              <span>
                Graphic Era<span>Hill University</span>
              </span>
            </a>
            <button
              className="mobile-toggle icon-button"
              aria-expanded={menu}
              aria-label="Toggle navigation"
              aria-controls="main-navigation"
              onClick={() => setMenu(!menu)}
            >
              {menu ? <X /> : <Menu />}
            </button>
          </div>
          <div className="navigation-bar">
            <div className="navigation-inner">
              <nav
                id="main-navigation"
                className={menu ? "navigation open" : "navigation"}
                aria-label="Main navigation"
                onClick={() => setMenu(false)}
              >
                <NavLink end to="/">
                  Room availability
                </NavLink>
                {canViewFaculty(user?.role) && (
                  <NavLink to="/teachers">Faculty timetable</NavLink>
                )}
                <NavLink to="/clubs">Clubs</NavLink>
                <NavLink to="/lab">OS & DBMS Lab</NavLink>
                {canRequest(user?.role) && (
                  <NavLink to="/requests">My requests</NavLink>
                )}
                {canReview(user?.role) && (
                  <NavLink to="/approvals">Review requests</NavLink>
                )}
                {user?.role === "ADMIN" && (
                  <>
                    <NavLink to="/admin/accounts">Accounts</NavLink>
                    <NavLink to="/admin/timetables">Timetables</NavLink>
                    <NavLink to="/admin/rooms">Room register</NavLink>
                  </>
                )}
                {user ? (
                  <div className="user-nav">
                    <div className="notification-wrapper" ref={notifRef}>
                      <button
                        className="notification-bell"
                        onClick={(e) => {
                          e.stopPropagation();
                          setNotifOpen(!notifOpen);
                        }}
                        aria-label={`Notifications${unreadCount > 0 ? ` (${unreadCount} unread)` : ""}`}
                        aria-expanded={notifOpen}
                        type="button"
                      >
                        <Bell size={17} />
                        {unreadCount > 0 && (
                          <span className="notification-badge">{unreadCount}</span>
                        )}
                      </button>
                      {notifOpen && (
                        <div
                          className="notification-dropdown"
                          onClick={(e) => e.stopPropagation()}
                        >
                          <div className="notification-dropdown-header">
                            <span>Notifications {unreadCount > 0 ? `(${unreadCount} unread)` : ""}</span>
                            {unreadCount > 0 && (
                              <button onClick={markAllRead} type="button">
                                <CheckCheck size={14} style={{ marginRight: "3px" }} />
                                Mark all read
                              </button>
                            )}
                          </div>
                          <ul className="notification-dropdown-list">
                            {notifications.length === 0 ? (
                              <li className="notification-empty">No notifications</li>
                            ) : (
                              notifications.map((n) => (
                                <li
                                  key={n.id}
                                  className={`notification-item ${n.is_read ? "" : "unread"}`}
                                  onClick={() => markRead(n.id, n.request_id)}
                                >
                                  <div>{n.message}</div>
                                  <div className="notification-item-time">
                                    {new Date(n.created_at).toLocaleString("en-IN", {
                                      timeZone: "Asia/Kolkata",
                                      dateStyle: "short",
                                      timeStyle: "short",
                                    })}
                                  </div>
                                </li>
                              ))
                            )}
                          </ul>
                        </div>
                      )}
                    </div>
                    <span>
                      {user.full_name}
                      <small>{roleName(user.role)}</small>
                    </span>
                    <button
                      className="icon-button"
                      onClick={signOut}
                      aria-label="Sign out"
                    >
                      <LogOut size={18} />
                    </button>
                  </div>
                ) : (
                  <Link to="/login" className="staff-login">
                    Sign in <ArrowUpRight size={15} />
                  </Link>
                )}
              </nav>
            </div>
          </div>
        </div>
      </header>
      {error && (
        <div role="alert" className="header-error">
          {error}
        </div>
      )}
      <main id="main" tabIndex={-1}>
        <Outlet />
      </main>
      <footer className="site-footer">
        <div className="container footer-inner">
          <div className="footer-brand">
            <Link to="/" className="footer-title">
              QuickSlot
            </Link>
            <p>Campus room schedules and booking requests.</p>
          </div>
          <nav className="footer-links" aria-label="Footer navigation">
            <h2>Explore</h2>
            <Link to="/">Room availability</Link>
            {canViewFaculty(user?.role) && (
              <Link to="/teachers">Faculty timetable</Link>
            )}
            <Link to="/clubs">Club profiles</Link>
            <Link to="/lab">OS & DBMS Lab</Link>
            <Link to="/login">Sign in</Link>
          </nav>
          <div className="footer-guidance">
            <h2>Using a room</h2>
            <p>
              Students can check availability. Faculty and verified club heads
              can request a booking.
            </p>
            <Link to="/requests/new">
              Request a room <ArrowUpRight size={14} />
            </Link>
          </div>
          <div className="footer-university">
            <h2>Graphic Era Hill University</h2>
            <a href="https://gehu.ac.in/" target="_blank" rel="noreferrer">
              University website <ArrowUpRight size={14} />
            </a>
          </div>
        </div>
        <div className="container footer-bottom">
          <span>QuickSlot · Campus room availability & allocation</span>
          <span>Check the room in person before use.</span>
        </div>
      </footer>
    </>
  );
}
