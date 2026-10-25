export type ScreenId = 'home' | 'stats' | 'device' | 'alarm' | 'locked' | 'clear'

export type TabId = 'home' | 'stats' | 'device'

export type WakeResult = {
  seconds: number
  rank: 'S' | 'A' | 'B' | 'C'
  xp: number
}

export function rankFor(seconds: number): WakeResult['rank'] {
  if (seconds <= 60) return 'S'
  if (seconds <= 180) return 'A'
  if (seconds <= 420) return 'B'
  return 'C'
}

export function xpFor(rank: WakeResult['rank']) {
  return { S: 150, A: 110, B: 70, C: 30 }[rank]
}

export function formatDuration(totalSeconds: number) {
  const m = Math.floor(totalSeconds / 60)
  const s = totalSeconds % 60
  return `${String(m).padStart(2, '0')}:${String(s).padStart(2, '0')}`
}
