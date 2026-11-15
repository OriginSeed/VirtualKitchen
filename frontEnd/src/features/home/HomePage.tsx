import { useRef, useState, type CSSProperties, type ReactNode } from 'react'
import './HomePage.css'
import chefLogo from '../../assets/kitchen/blackShadowChef.png'
import AppFooter from '../../shared/components/AppFooter'
import { useTheme } from '../../shared/theme/themeContext'
import AiDemo from './AiDemo'
import HeroFlow from './HeroFlow'
import { useReveal } from './useReveal'

type Props = {
  /** Whether a session is active (a stored token alone may still fail to restore). */
  signedIn?: boolean
  onTryIt?: () => void
  onLogin?: () => void
}

const order = (i: number) => ({ '--i': i }) as CSSProperties

const FEATURES = [
  { icon: '🧩', title: 'Recipe editor', text: 'Build a recipe by hand: Preparation flows and a Main process, each step edited in its own card with a Description and an Expected result. Autosave and undo keep it safe.' },
  { icon: '✦', title: 'Build with AI', text: 'Describe a dish and AI drafts the whole recipe, or ask it to continue or tweak the one you have. You review before anything changes.' },
  { icon: '✍️', title: 'Easy step building', text: 'Type @ to tag ingredients, amounts and equipment in a step. Titles are written for you from the action, so building a recipe stays quick.' },
  { icon: '▶', title: 'Visuals & narration', text: 'See an image for each preparation and play the recipe as a narrated, captioned walkthrough, so every step is clear.' },
  { icon: '🥕', title: 'Ingredients & nutrition', text: 'Pick quantities from a real ingredient catalog and see the nutrition of the whole recipe.' },
  { icon: '📚', title: 'Recipe library', text: 'Keep your own recipes, browse public recipes in Discover, read any of them and add a copy to your kitchen to make it yours.' },
]

const STEPS = [
  { title: 'Create', text: 'Add Preparation and Main steps, tag ingredients with @ and set quantities.' },
  { title: 'Refine with AI', text: 'Generate a flow from a sentence or extend the one you have.' },
  { title: 'Visualize', text: 'Play it back as a narrated, step-by-step walkthrough.' },
  { title: 'Cook or order', text: 'Cook it yourself in cook mode — or, soon, have our chefs prepare and deliver it.' },
]

const ORDER_STAGES = ['Confirm', 'Ingredients', 'Payment', 'Receipt', 'Tracking']

const FAQS = [
  { q: 'Do I need cooking or technical experience?', a: 'No. Start from scratch, add a public recipe from Discover to your kitchen, or describe a dish and let AI draft it for you.' },
  { q: 'How do AI credits work?', a: 'Every account gets a monthly AI credit allowance, visible in the AI menu. Bigger AI workflows show an estimate and wait for your approval before they run.' },
  { q: 'Who can see my recipes?', a: 'You choose. Keep a recipe private in My Recipes, or publish it to Discover so others can view it and add it to their kitchen.' },
  { q: 'Can I order a recipe today?', a: 'Chef-prepared delivery is coming soon. Until then you can try a demo of the ordering flow: it checks your pantry, reserves what you already have and lets you shop for the rest, with simulated payment, preparation and delivery — nothing is cooked or charged.' },
  { q: 'What is the narrated walkthrough?', a: 'It turns your recipe flow into a step-by-step slideshow with captions that follow a spoken narration, so you can cook hands-free.' },
]

export default function HomePage({ signedIn = false, onTryIt, onLogin }: Props) {
  const hideLogin = signedIn
  const { theme, toggleTheme } = useTheme()
  const rootRef = useRef<HTMLDivElement>(null)
  useReveal(rootRef)
  // Narrow screens: the section links fold into a menu.
  const [menuOpen, setMenuOpen] = useState(false)

  return (
    <div className="home-page" ref={rootRef}>
      <header className="home-nav">
        <a className="home-brand" href="#top">
          <span className="home-logo"><img src={chefLogo} alt="" /></span>
          Virtual Kitchen
        </a>
        <nav
          id="home-section-links"
          className={`home-nav-links${menuOpen ? ' is-open' : ''}`}
          aria-label="Home sections"
          onClick={(event) => {
            if ((event.target as HTMLElement).closest('a')) setMenuOpen(false)
          }}
        >
          <a href="#features">Features</a>
          <a href="#how-it-works">How it works</a>
          <a href="#ai">AI</a>
          <a href="#ordering">Ordering</a>
          <a href="#faq">FAQ</a>
        </nav>
        <div className="home-nav-actions">
          <button
            type="button"
            className="home-menu-toggle"
            aria-expanded={menuOpen}
            aria-controls="home-section-links"
            aria-label={menuOpen ? 'Close menu' : 'Open menu'}
            onClick={() => setMenuOpen((open) => !open)}
          >
            {menuOpen ? '✕' : '☰'}
          </button>
          <button
            type="button"
            className="home-theme-toggle"
            onClick={toggleTheme}
            aria-label={theme === 'dark' ? 'Switch to light mode' : 'Switch to dark mode'}
            title={theme === 'dark' ? 'Switch to light mode' : 'Switch to dark mode'}
          >
            {theme === 'dark' ? '☀️' : '🌙'}
          </button>
          {!hideLogin && (
            <button type="button" className="home-btn home-btn-ghost" onClick={onLogin}>Log in</button>
          )}
          <button type="button" className="home-btn home-btn-primary" onClick={onTryIt}>
            {hideLogin ? 'Open Kitchen' : 'Get started'}
          </button>
        </div>
      </header>

      <main id="top">
        <section className="home-hero">
          <div className="home-hero-glow" aria-hidden="true" />
          <div className="home-hero-copy">
            <span className="home-eyebrow">You are the chef</span>
            <h1>Cook your way. <em>Create your rules.</em></h1>
            <p>
              From a simple family recipe to something entirely your own, create every
              step the way you want. AI helps you bring your recipe to life—you stay in
              control.
            </p>
            <div className="home-hero-actions">
              <button type="button" className="home-btn home-btn-primary home-btn-lg" onClick={onTryIt}>
                Create your first recipe <span aria-hidden="true">→</span>
              </button>
              <a className="home-btn home-btn-secondary home-btn-lg" href="#how-it-works">See how it works</a>
            </div>
            <ul className="home-ticks">
              <li>You stay in control</li>
              <li>AI drafts, you approve</li>
              <li>From flow to doorstep</li>
            </ul>
          </div>
          <HeroFlow />
        </section>

        <section className="home-section" id="features">
          <SectionHeading eyebrow="Features" title="Everything your recipe needs.">
            One workspace to design, understand, share and shop for a recipe.
          </SectionHeading>
          <div className="home-feature-grid">
            {FEATURES.map((feature, i) => (
              <article key={feature.title} className="home-card" data-reveal style={order(i)}>
                <span className="home-card-icon" aria-hidden="true">{feature.icon}</span>
                <h3>{feature.title}</h3>
                <p>{feature.text}</p>
              </article>
            ))}
          </div>
        </section>

        <section className="home-section home-band" id="how-it-works">
          <SectionHeading eyebrow="How it works" title="From an idea to a cooking process.">
            Four steps, and you decide what happens at every one of them.
          </SectionHeading>
          <ol className="home-steps" data-reveal>
            {STEPS.map((step, i) => (
              <li key={step.title} className="home-step" style={order(i)}>
                <span className="home-step-number">{String(i + 1).padStart(2, '0')}</span>
                <h3>{step.title}</h3>
                <p>{step.text}</p>
              </li>
            ))}
          </ol>
        </section>

        <section className="home-section home-ai" id="ai">
          <div className="home-split">
            <div className="home-split-copy" data-reveal>
              <span className="home-eyebrow">AI assistant</span>
              <h2>AI assists. You decide.</h2>
              <p>
                Ask for a whole recipe, a tweak or the next few steps. AI proposes a plan,
                and nothing changes in your recipe until you approve it.
              </p>
              <ul className="home-checklist">
                <li><b>Approval checkpoint</b> Review the plan before any flow is built.</li>
                <li><b>Continue, don’t restart</b> Extend an existing flow from a prompt.</li>
                <li><b>Transparent credits</b> A monthly allowance you can always see.</li>
              </ul>
            </div>
            <div data-reveal style={order(1)}>
              <AiDemo />
            </div>
          </div>
        </section>

        <section className="home-section" id="ordering">
          <div className="home-split is-reversed">
            <div className="home-split-copy" data-reveal>
              <span className="home-eyebrow">Recipe ordering · Coming soon</span>
              <h2>From recipe to doorstep.</h2>
              <p>
                Soon our chefs will cook any recipe in your kitchen exactly as it is written and deliver
                it to your door — your recipe flow is their instructions.
              </p>
              <p className="home-roadmap">
                Try the demo today: pick your servings, see what your pantry already covers, shop for
                the rest and follow a simulated order to the door.
              </p>
            </div>
            <div className="home-order-card" data-reveal style={order(1)}>
              <ol className="home-order-track" aria-label="Recipe order stages">
                {ORDER_STAGES.map((stage, i) => (
                  <li key={stage} style={order(i)} className={i === 1 ? 'is-current' : i < 1 ? 'is-done' : ''}>
                    <span className="home-order-dot">{i < 1 ? '✓' : i + 1}</span>
                    {stage}
                  </li>
                ))}
              </ol>
              <div className="home-order-items">
                <OrderItem name="Basmati rice" detail="250 g" status="In pantry" tone="success" />
                <OrderItem name="Eggs" detail="2" status="Reserved" tone="info" />
                <OrderItem name="Spring onions" detail="1 bunch" status="Add to cart" tone="warning" />
              </div>
            </div>
          </div>
        </section>

        <section className="home-section home-band" id="faq">
          <SectionHeading eyebrow="FAQ" title="Good questions." />
          <div className="home-faq" data-reveal>
            {FAQS.map((item) => (
              <details key={item.q}>
                <summary>{item.q}</summary>
                <p>{item.a}</p>
              </details>
            ))}
          </div>
        </section>

        <section className="home-cta">
          <div className="home-cta-card" data-reveal>
            <span className="home-eyebrow">Ready?</span>
            <h2>Start cooking your way.</h2>
            <p>Create your first recipe in minutes, with AI at your side.</p>
            <button type="button" className="home-btn home-btn-primary home-btn-lg" onClick={onTryIt}>
              Try Virtual Kitchen <span aria-hidden="true">→</span>
            </button>
          </div>
        </section>
      </main>

      <AppFooter />
    </div>
  )
}

function SectionHeading({ eyebrow, title, children }: { eyebrow: string; title: string; children?: ReactNode }) {
  return (
    <div className="home-heading" data-reveal>
      <span className="home-eyebrow">{eyebrow}</span>
      <h2>{title}</h2>
      {children && <p>{children}</p>}
    </div>
  )
}

function OrderItem({ name, detail, status, tone }: {
  name: string
  detail: string
  status: string
  tone: 'success' | 'info' | 'warning'
}) {
  return (
    <div className="home-order-item">
      <div><b>{name}</b><small>{detail}</small></div>
      <span className={`home-pill is-${tone}`}>{status}</span>
    </div>
  )
}
