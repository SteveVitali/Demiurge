class Demiurge < Formula
  desc "AI-powered web development automation platform"
  homepage "https://demiurge.dev"
  version "0.2.0"
  license "BSL-1.1"

  depends_on "openjdk@17"

  on_macos do
    if Hardware::CPU.arm?
      url "https://github.com/SteveVitali/Demiurge/releases/download/v#{version}/demiurge-cli-v#{version}-macos-arm64.tar.gz"
      sha256 "PLACEHOLDER_SHA256"
    else
      url "https://github.com/SteveVitali/Demiurge/releases/download/v#{version}/demiurge-cli-v#{version}-macos-x64.tar.gz"
      sha256 "PLACEHOLDER_SHA256"
    end
  end

  on_linux do
    url "https://github.com/SteveVitali/Demiurge/releases/download/v#{version}/demiurge-cli-v#{version}-linux-x64.tar.gz"
    sha256 "PLACEHOLDER_SHA256"
  end

  def install
    libexec.install "demiurge.jar"

    (bin/"demiurge").write <<~EOS
      #!/bin/bash
      exec "#{Formula["openjdk@17"].opt_bin}/java" -Xmx512m -jar "#{libexec}/demiurge.jar" "$@"
    EOS
  end

  test do
    assert_match "demiurge", shell_output("#{bin}/demiurge --help")
  end
end
