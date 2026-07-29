import type { ReactNode } from 'react'
import { desktopNavigation, mainNavigation, type Section } from '../navigation'
import { Icon, type IconName } from '../components/Icon'

type Navigate = (section: Section) => void

export function DesktopShell({ active, onNavigate, queueOpen, queue }: { active: Section; onNavigate: Navigate; queueOpen: boolean; queue: ReactNode }) {
  return <>
    <aside className="sidebar" aria-label="主导航">
      <Brand />
      <nav className="nav-list">{desktopNavigation.map((item) => <NavButton key={item.id} item={item} active={active === item.id} onClick={() => onNavigate(item.id)} />)}</nav>
      <button className={`nav-settings ${active === 'settings' ? 'active' : ''}`} onClick={() => onNavigate('settings')} aria-current={active === 'settings' ? 'page' : undefined}><Icon name="settings" /><span>设置</span></button>
    </aside>
    <aside id="play-queue" className={`queue-panel ${queueOpen ? '' : 'closed'}`} aria-label="播放队列和相似音乐">{queue}</aside>
  </>
}

export function MobileShell({ active, onNavigate }: { active: Section; onNavigate: Navigate }) {
  return <nav className="mobile-nav" aria-label="移动端主导航">{mainNavigation.map((item) => <NavButton key={item.id} item={item} active={active === item.id} onClick={() => onNavigate(item.id)} />)}</nav>
}

function NavButton({ item, active, onClick }: { item: { id: Section; label: string; icon: string }; active: boolean; onClick: () => void }) {
  return <button className={`nav-button ${active ? 'active' : ''}`} onClick={onClick} aria-current={active ? 'page' : undefined}><Icon name={item.icon as IconName} /><span>{item.label}</span></button>
}

function Brand() {
  return <div className="brand"><span className="brand-icon">♪</span><span><strong>SHiNe</strong><small>家庭音乐</small></span></div>
}
