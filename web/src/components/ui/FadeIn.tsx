'use client';

import { motion, type HTMLMotionProps } from 'framer-motion';

interface FadeInProps extends HTMLMotionProps<'div'> {
  delay?: number;
  duration?: number;
  once?: boolean;
  y?: number;
}

export function FadeIn({
  delay = 0,
  duration = 0.6,
  once = true,
  y = 20,
  children,
  ...props
}: FadeInProps) {
  return (
    <motion.div
      initial={{ opacity: 0, y }}
      whileInView={{ opacity: 1, y: 0 }}
      viewport={{ once }}
      transition={{ duration, delay }}
      {...props}
    >
      {children}
    </motion.div>
  );
}
