/** @type {import('tailwindcss').Config} */
module.exports = {
  darkMode: ["class"],
  content: ["./index.html", "./src/**/*.{ts,tsx}"],
  theme: {
    extend: {
      colors: {
        paper: "var(--color-paper)",
        "paper-2": "var(--color-paper-2)",
        "paper-3": "var(--color-paper-3)",
        ink: "var(--color-ink)",
        "ink-2": "var(--color-ink-2)",
        muted: "var(--color-muted)",
        rule: "var(--color-rule)",
        "rule-2": "var(--color-rule-2)",
        graphite: "var(--color-graphite)",
        "graphite-2": "var(--color-graphite-2)",
        "graphite-ink": "var(--color-graphite-ink)",
        "graphite-muted": "var(--color-graphite-muted)",
        "graphite-rule": "var(--color-graphite-rule)",
        accent: "var(--color-accent)",
        "accent-2": "var(--color-accent-2)",
        "accent-wash": "var(--color-accent-wash)",
        "accent-rule": "var(--color-accent-rule)",
        "accent-deep": "var(--color-accent-deep)",
        "accent-ink": "var(--color-accent-ink)",
        focus: "var(--color-focus)",
        success: "var(--color-success)",
        "success-wash": "var(--color-success-wash)",
        "success-rule": "var(--color-success-rule)",
        warning: "var(--color-warning)",
        "warning-wash": "var(--color-warning-wash)",
        "warning-rule": "var(--color-warning-rule)",
        error: "var(--color-error)",
        "error-wash": "var(--color-error-wash)",
        "error-rule": "var(--color-error-rule)",
        info: "var(--color-info)",
        "info-wash": "var(--color-info-wash)",
        "info-rule": "var(--color-info-rule)",
        background: "var(--color-paper-2)",
        foreground: "var(--color-ink)",
        card: "var(--color-paper)",
        "card-foreground": "var(--color-ink)",
        popover: "var(--color-paper)",
        "popover-foreground": "var(--color-ink)",
        primary: "var(--color-accent)",
        "primary-foreground": "var(--color-accent-ink)",
        secondary: "var(--color-paper-2)",
        "secondary-foreground": "var(--color-ink)",
        muted: "var(--color-paper-2)",
        "muted-foreground": "var(--color-muted)",
        accent: "var(--color-accent)",
        "accent-foreground": "var(--color-accent-ink)",
        destructive: "var(--color-error)",
        "destructive-foreground": "var(--color-accent-ink)",
        border: "var(--color-rule)",
        input: "var(--color-rule-2)",
        ring: "var(--color-focus)",
        "chat-user": "var(--color-accent-wash)",
        "chat-assistant": "var(--color-paper)"
      },
      fontFamily: {
        display: ["'Space Grotesk'", "ui-sans-serif", "system-ui"],
        body: ["'DM Sans'", "ui-sans-serif", "system-ui"],
        mono: ["'JetBrains Mono'", "ui-monospace", "SFMono-Regular"]
      },
      boxShadow: {
        card: "var(--shadow-card)",
        divider: "-1px 0 0 var(--color-rule-2)",
        soft: "var(--shadow-card)",
        glow: "0 0 0 1px color-mix(in oklab, var(--color-accent) 20%, transparent), 0 16px 40px color-mix(in oklab, var(--color-accent) 25%, transparent)",
        neon: "0 0 30px color-mix(in oklab, var(--color-accent) 35%, transparent)"
      },
      keyframes: {
        "fade-up": {
          "0%": { opacity: 0, transform: "translateY(10px)" },
          "100%": { opacity: 1, transform: "translateY(0)" }
        },
        "pulse-soft": {
          "0%, 100%": { opacity: 1 },
          "50%": { opacity: 0.5 }
        },
        "blink": {
          "0%, 100%": { opacity: 1 },
          "50%": { opacity: 0 }
        },
        "spin-slow": {
          "0%": { transform: "rotate(0deg)" },
          "100%": { transform: "rotate(360deg)" }
        },
        "glow": {
          "0%, 100%": { opacity: 0.5 },
          "50%": { opacity: 1 }
        },
        "float": {
          "0%, 100%": { transform: "translateY(0)" },
          "50%": { transform: "translateY(-6px)" }
        }
      },
      animation: {
        "fade-up": "fade-up 0.35s ease-out",
        "pulse-soft": "pulse-soft 1.4s ease-in-out infinite",
        "blink": "blink 1s step-end infinite",
        "spin-slow": "spin-slow 4s linear infinite",
        "glow": "glow 2.6s ease-in-out infinite",
        "float": "float 6s ease-in-out infinite"
      },
      backgroundImage: {
        "gradient-radial": "radial-gradient(var(--tw-gradient-stops))",
        "grid-pattern":
          "linear-gradient(rgba(255,255,255,0.06) 1px, transparent 1px), linear-gradient(90deg, rgba(255,255,255,0.06) 1px, transparent 1px)"
      }
    }
  },
  plugins: [require("@tailwindcss/typography")]
};
