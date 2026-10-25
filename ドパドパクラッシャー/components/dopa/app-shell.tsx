'use client'

import { useCallback, useState } from 'react'
import { BarChart3, Cpu, Home } from 'lucide-react'
import { cn } from '@/lib/utils'
import { AlarmScreen } from './alarm-screen'
import { ClearScreen } from './clear-screen'
import { DeviceScreen } from './device-screen'
import { HomeScreen } from './home-screen'
import { LockScreen } from './lock-screen'
import { StatusBar } from './primitives'
import { StatsScreen } from './stats-screen'
import { rankFor, xpFor, type ScreenId, type TabId, type WakeResult } from './types'

const tabs: { id: TabId; label: string; icon: typeof Home }[] = [
  { id: 'home', label: 'ホーム', icon: Home },
  { id: 'stats', label: '戦績', icon: BarChart3 },
  { id: 'device', label: 'デバイス', icon: Cpu },
]

export const flowSteps: { id: ScreenId; label: string }[] = [
  { id: 'home', label: 'ホーム' },
  { id: 'alarm', label: 'アラーム' },
  { id: 'locked', label: 'ベッド在床ロック' },
  { id: 'clear', label: 'ミッションクリア' },
  { id: 'stats', label: '戦績' },
  { id: 'device', label: 'デバイス設定' },
]

export function useDopaApp() {
  const [screen, setScreen] = useState<ScreenId>('home')
  const [xp, setXp] = useState(1262)
  const [streak, setStreak] = useState(5)
  const [result, setResult] = useState<WakeResult>({ seconds: 134, rank: 'A', xp: 110 })
  const [xpBefore, setXpBefore] = useState(1262)

  const handleLeaveBed = useCallback(
    (seconds: number) => {
      const rank = rankFor(seconds)
      setResult({ seconds, rank, xp: xpFor(rank) })
      setXpBefore(xp)
      setStreak((s) => s + 1)
      setScreen('clear')
    },
    [xp],
  )

  const handleDone = useCallback(() => {
    setXp((v) => v + result.xp)
    setScreen('home')
  }, [result.xp])

  return { screen, setScreen, xp, streak, result, xpBefore, handleLeaveBed, handleDone }
}

export function AppShell({ app }: { app: ReturnType<typeof useDopaApp> }) {
  const { screen, setScreen, xp, streak, result, xpBefore, handleLeaveBed, handleDone } = app
  const level = Math.floor(xp / 100)
  const isTab = screen === 'home' || screen === 'stats' || screen === 'device'

  return (
    <div className="flex h-full flex-col bg-background">
      <StatusBar />
      {isTab ? (
        <>
          <main key={screen} className="flex-1 animate-rise overflow-y-auto scrollbar-none">
            {screen === 'home' && (
              <HomeScreen level={level} xp={xp} streak={streak} onRingAlarm={() => setScreen('alarm')} />
            )}
            {screen === 'stats' && <StatsScreen level={level} streak={streak} />}
            {screen === 'device' && <DeviceScreen />}
          </main>
          <nav aria-label="メインナビゲーション" className="shrink-0 px-5 pb-6 pt-2">
            <ul className="flex items-center justify-between rounded-full border bg-surface/90 p-1.5 backdrop-blur">
              {tabs.map(({ id, label, icon: Icon }) => {
                const active = screen === id
                return (
                  <li key={id} className="flex-1">
                    <button
                      type="button"
                      onClick={() => setScreen(id)}
                      aria-current={active ? 'page' : undefined}
                      className={cn(
                        'flex w-full items-center justify-center gap-1.5 rounded-full py-2.5 text-xs font-bold transition',
                        active ? 'bg-lime text-lime-foreground' : 'text-muted-foreground hover:text-foreground',
                      )}
                    >
                      <Icon className="size-4" aria-hidden="true" />
                      {label}
                    </button>
                  </li>
                )
              })}
            </ul>
          </nav>
        </>
      ) : (
        <main key={screen} className="min-h-0 flex-1 animate-in fade-in duration-300">
          {screen === 'alarm' && <AlarmScreen onWake={() => setScreen('locked')} />}
          {screen === 'locked' && <LockScreen onLeaveBed={handleLeaveBed} />}
          {screen === 'clear' && (
            <ClearScreen result={result} streak={streak} level={level} xpBefore={xpBefore} onDone={handleDone} />
          )}
        </main>
      )}
    </div>
  )
}
