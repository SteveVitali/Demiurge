import { Hero } from '@/components/landing/Hero';
import { DemoVideo } from '@/components/landing/DemoVideo';
import { FeaturesGrid } from '@/components/landing/FeaturesGrid';
import { HowItWorks } from '@/components/landing/HowItWorks';
import { Testimonials } from '@/components/landing/Testimonials';
import { CTABanner } from '@/components/landing/CTABanner';

export default function HomePage() {
  return (
    <>
      <Hero />
      <DemoVideo />
      <FeaturesGrid />
      <HowItWorks />
      <Testimonials />
      <CTABanner />
    </>
  );
}
