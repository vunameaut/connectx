export const Colors = {
  primary: '#2563EB',       // Blue 600
  primaryHover: '#1D4ED8',  // Blue 700
  primaryLight: '#EFF6FF',  // Blue 50
  secondary: '#475569',     // Slate 600
  success: '#16A34A',       // Green 600
  warning: '#D97706',       // Amber 600
  danger: '#DC2626',        // Red 600
  dangerLight: '#FEF2F2',   // Red 50

  // Neutral tones
  background: '#FFFFFF',
  backgroundSecondary: '#F8FAFC',
  surface: '#FFFFFF',
  surfaceBorder: '#E2E8F0',
  textPrimary: '#0F172A',   // Slate 900
  textSecondary: '#64748B', // Slate 500
  textMuted: '#94A3B8',     // Slate 400

  // Dark Mode Overrides
  dark: {
    background: '#0F172A',
    backgroundSecondary: '#1E293B',
    surface: '#1E293B',
    surfaceBorder: '#334155',
    textPrimary: '#F8FAFC',
    textSecondary: '#94A3B8',
    textMuted: '#64748B',
  },

  // Screen share & Call indicator
  activeShare: '#EF4444',   // Red pulse
  callActive: '#22C55E',    // Green active
} as const;

export const Typography = {
  fontFamily: {
    regular: 'System',
    medium: 'System-Medium',
    bold: 'System-Bold',
  },
  sizes: {
    xs: 12,
    sm: 14,
    base: 16,
    lg: 18,
    xl: 20,
    '2xl': 24,
    '3xl': 30,
  },
  lineHeights: {
    xs: 16,
    sm: 20,
    base: 24,
    lg: 28,
    xl: 28,
    '2xl': 32,
    '3xl': 36,
  },
} as const;

export const Spacing = {
  xs: 4,
  sm: 8,
  md: 16,
  lg: 24,
  xl: 32,
  '2xl': 48,
} as const;

export const BorderRadius = {
  sm: 4,
  md: 8,
  lg: 12,
  xl: 16,
  full: 9999,
} as const;
