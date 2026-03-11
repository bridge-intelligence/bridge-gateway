import { useState } from 'react';
import { Outlet, useNavigate, useLocation } from 'react-router-dom';
import {
  Header,
  HeaderName,
  HeaderNavigation,
  HeaderMenuItem,
  HeaderGlobalBar,
  HeaderGlobalAction,
  SideNav,
  SideNavItems,
  SideNavLink,
  Content,
} from '@carbon/react';
import {
  Dashboard as DashboardIcon,
  DataConnected,
  PlugFilled,
  Checkmark,
  Logout,
} from '@carbon/icons-react';
import { useAuthStore } from '../../stores/authStore';

export default function DashboardLayout() {
  const navigate = useNavigate();
  const location = useLocation();
  const { email, logout } = useAuthStore();
  const [sideNavExpanded, setSideNavExpanded] = useState(true);

  const handleLogout = () => {
    logout();
    navigate('/login');
  };

  const navItems = [
    { path: '/dashboard', label: 'Dashboard', icon: DashboardIcon },
    { path: '/routes', label: 'Routes', icon: DataConnected },
    { path: '/plugins', label: 'Plugins', icon: PlugFilled },
    { path: '/health', label: 'Health', icon: Checkmark },
  ];

  return (
    <>
      <Header aria-label="Bridge Gateway">
        <HeaderName href="#/" prefix="Bridge">
          Gateway
        </HeaderName>
        <HeaderNavigation aria-label="Navigation">
          {navItems.map((item) => (
            <HeaderMenuItem
              key={item.path}
              href={`#${item.path}`}
              isCurrentPage={location.pathname === item.path}
              onClick={(e: React.MouseEvent) => {
                e.preventDefault();
                navigate(item.path);
              }}
            >
              {item.label}
            </HeaderMenuItem>
          ))}
        </HeaderNavigation>
        <HeaderGlobalBar>
          <HeaderGlobalAction aria-label={email ?? 'User'} tooltipAlignment="end">
            <span style={{ fontSize: '0.75rem', color: '#c6c6c6' }}>{email}</span>
          </HeaderGlobalAction>
          <HeaderGlobalAction aria-label="Logout" tooltipAlignment="end" onClick={handleLogout}>
            <Logout size={20} />
          </HeaderGlobalAction>
        </HeaderGlobalBar>
        <SideNav
          aria-label="Side navigation"
          expanded={sideNavExpanded}
          onSideNavBlur={() => setSideNavExpanded(false)}
          onOverlayClick={() => setSideNavExpanded(false)}
        >
          <SideNavItems>
            {navItems.map((item) => (
              <SideNavLink
                key={item.path}
                renderIcon={item.icon}
                isActive={location.pathname === item.path}
                onClick={() => navigate(item.path)}
              >
                {item.label}
              </SideNavLink>
            ))}
          </SideNavItems>
        </SideNav>
      </Header>
      <Content style={{ marginTop: '3rem' }}>
        <Outlet />
      </Content>
    </>
  );
}
