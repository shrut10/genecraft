# Contributing to GeneCraft

Thanks for helping improve GeneCraft. Issues and pull requests are welcome.

## Before proposing a change

- Check existing issues and discussions for related work.
- Keep a proposal focused and explain who benefits and how to verify it.
- For new model actions, define the exact input schema, validate it in both the bridge and Paper plugin, and keep the server-side behavior narrow.

## Development checks

Use Java 21+, Maven 3.8+, and Python 3.10+. Before opening a pull request, run:

```sh
mvn --batch-mode --no-transfer-progress verify
python3 -m pip install -r bridge/requirements.txt
python3 -m unittest discover -s tests -v
```

The GitHub Actions workflow also runs these checks on Ubuntu, macOS, and Windows. Please mention any manual in-game testing and Paper/Minecraft versions in the pull request.

## Project expectations

- Preserve the loopback-only bridge default and never log, commit, or request users' OAuth tokens.
- Keep AI output behind server-side validation. Avoid arbitrary commands, code execution, file access, unrestricted coordinates, and unbounded world changes.
- Document what world context is sent to OpenAI and when model calls can happen.
- Do not add Minecraft game files, proprietary assets, copied skins, or the Paper server distribution to the repository. Use source code and assets that you have permission to redistribute.
- Keep the non-affiliation statement in public distribution materials.

By submitting a contribution, you agree that it may be distributed under the repository's MIT License.
