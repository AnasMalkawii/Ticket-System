import { ArrowUpRight } from 'lucide-react'

export function Poster({ id, name, large = false }: { id: string; name: string; large?: boolean }) {
  const theme = [...id].reduce((hash, char) => hash + char.charCodeAt(0), 0) % 4
  return (
    <div className={`poster poster-${theme}${large ? ' poster-large' : ''}`} aria-hidden="true">
      <div className="poster-top">
        <span>ENCORE PRESENTS</span>
        <ArrowUpRight size={22} strokeWidth={1.4} />
      </div>
      <div className="poster-art">
        <div className="poster-orbit" />
        <div className="poster-disc" />
        <div className="poster-line" />
      </div>
      <div className="poster-name">{name}</div>
      <div className="poster-bottom">
        <span>BE THERE.</span>
        <span>LIVE & IN PERSON</span>
      </div>
    </div>
  )
}
