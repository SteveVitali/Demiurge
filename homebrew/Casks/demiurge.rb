cask "demiurge" do
  version "0.2.0"
  sha256 "PLACEHOLDER_SHA256"

  url "https://github.com/SteveVitali/Demiurge/releases/download/v#{version}/Demiurge_#{version}_aarch64.dmg",
      verified: "github.com/SteveVitali/Demiurge/"
  name "Demiurge"
  desc "AI-powered web development automation platform"
  homepage "https://demiurge.dev"

  livecheck do
    url :url
    strategy :github_latest
  end

  app "Demiurge.app"

  zap trash: [
    "~/.demiurge",
    "~/Library/Application Support/com.demiurge.desktop",
  ]
end
