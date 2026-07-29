import type { ReactNode } from 'react'
import { downloadNavigation, mainNavigation, roomNavigation, type Section } from '../navigation'
import { Icon, type IconName } from '../components/Icon'
import shineLogoUrl from '../../../SHiNe.png'

type Navigate = (section: Section) => void

export function DesktopShell({ active, onNavigate, activeDownloads, queueOpen, queue }: { active: Section; onNavigate: Navigate; activeDownloads: number; queueOpen: boolean; queue: ReactNode }) {
  return <>
    <aside className="sidebar" aria-label="主导航">
      <Brand />
      <nav className="nav-list">{mainNavigation.map((item) => <NavButton key={item.id} item={item} active={active === item.id} onClick={() => onNavigate(item.id)} />)}</nav>
      <nav className="nav-list nav-utilities" aria-label="任务与同步">
        <NavButton item={downloadNavigation} active={active === downloadNavigation.id} badge={activeDownloads} onClick={() => onNavigate(downloadNavigation.id)} />
        <NavButton item={roomNavigation} active={active === roomNavigation.id} onClick={() => onNavigate(roomNavigation.id)} />
      </nav>
      <button className={`nav-settings ${active === 'settings' ? 'active' : ''}`} onClick={() => onNavigate('settings')} aria-current={active === 'settings' ? 'page' : undefined}><Icon name="settings" /><span>设置</span></button>
    </aside>
    <aside id="play-queue" className={`queue-panel ${queueOpen ? '' : 'closed'}`} aria-label="播放队列和相似音乐">{queue}</aside>
  </>
}

export function MobileShell({ active, onNavigate }: { active: Section; onNavigate: Navigate }) {
  return <nav className="mobile-nav" aria-label="移动端主导航">{mainNavigation.map((item) => <NavButton key={item.id} item={item} active={active === item.id} onClick={() => onNavigate(item.id)} />)}</nav>
}

function NavButton({ item, active, badge = 0, onClick }: { item: { id: Section; label: string; icon: string }; active: boolean; badge?: number; onClick: () => void }) {
  return <button className={`nav-button ${active ? 'active' : ''}`} onClick={onClick} aria-current={active ? 'page' : undefined} aria-label={badge ? `${item.label}，${badge} 个进行中` : item.label}><Icon name={item.icon as IconName} /><span>{item.label}</span>{badge > 0 && <b className="nav-badge">{badge > 99 ? '99+' : badge}</b>}</button>
}

function Brand() {
  return <div className="brand"><span className="brand-icon"><img src={shineLogoUrl} alt="" /></span><span><strong>SHiNe</strong><small>家庭音乐</small></span></div>
}
