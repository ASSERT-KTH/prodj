{
  description = "ProDJ - Serializing Java Objects in Plain Code";

  inputs = {
    nixpkgs.url = "github:NixOS/nixpkgs/nixos-23.05";
  };

  outputs = { self, nixpkgs }:
    let
      forAllSystems = nixpkgs.lib.genAttrs nixpkgs.lib.systems.flakeExposed;
      mkShell = system: { javaVersion }:
        let
          pkgs = import nixpkgs {
            inherit system;
            overlays = [
              (final: prev: rec {
                jdk = pkgs."jdk${toString javaVersion}";
                maven = prev.maven.override { inherit jdk; };
              })
            ];
          };
          patched-spoon = pkgs.stdenv.mkDerivation {
            name = "spoon-patched";
            version = "adedea97ebd6561cad424701760b5c9ef759cd82";
            patches = [ ./spoon.patch ];
            src = pkgs.fetchFromGitHub {
              owner = "inria";
              repo = "spoon";
              rev = "adedea97ebd6561cad424701760b5c9ef759cd82";
              sha256 = "sha256-J85C/jTaT840AZkO8IvOrqFmIF2KxmvQsGK5hhrksRI=";
            };
            nativeBuildInputs = with pkgs; [ maven ];
            buildPhase = ''
            mkdir -p tmp
            mvn -f spoon-pom clean install -Dmaven.test.skip=true -DskipDepClean=true -Dmaven.repo.local=tmp

            rm tmp/fr/inria/gforge/spoon/*/*/_remote.repositories
            rm tmp/fr/inria/gforge/spoon/*/*/maven-metadata-local.xml
            rm tmp/fr/inria/gforge/spoon/*/maven-metadata-local.xml

            mkdir -p "$out/fr/inria/gforge/spoon"
            cp -r tmp/fr/inria/gforge/spoon/* "$out/fr/inria/gforge/spoon"
            '';
            outputHash = "sha256-T8IOeQs9lDfiXdHsy7AP7FC8lkKdatRkZNN2MpzqxmY=";
            outputHashMode = "recursive";
            outputHashAlgo = "sha256";
          };
        in
        pkgs.mkShell rec {
          test = pkgs.writeScriptBin "test" ''
            # Prepare repository
            mkdir -p .repo
            cp -r "${patched-spoon}"/* .repo/ && chmod +w -R .repo

            mvn clean package -Dmaven.repo.local=.repo
          '';
          packages = with pkgs; [ jdk maven test patched-spoon ];
        };
    in
    {
      devShells =
        forAllSystems
          (system:
            rec {
              default = mkShell system { javaVersion = 17; };
            });
    };
}
