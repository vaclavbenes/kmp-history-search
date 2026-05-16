# kmp-history-search

macOS desktop app that indexes browser history from Chrome, Zen, and Thorium and provides real-time fuzzy search across all of them with a keyboard-driven UI.

## Features
- Multi-browser aggregation (Chrome, Zen, Thorium)
- Real-time fuzzy search with relevance scoring
- Favicon display per result
- System tray integration with global hotkey
- Full keyboard navigation — no mouse required
- Search suggestions / autocomplete from recent tokens
- Visit count and last-visit timestamp per result

## Requirements
- macOS
- JDK (Gradle downloads dependencies automatically)

## Build & Run
```
./gradlew run          # run in dev mode
./gradlew packageDmg   # build distributable DMG
```

## Supported Browsers
| Browser | History | Favicons |
|---------|---------|----------|
| Chrome  | ✅      | ✅       |
| Zen     | ✅      | ✅       |
| Thorium | ✅      | ✅       |

## Keyboard Shortcuts

### Global
| Shortcut | Action             |
|----------|--------------------|
| Cmd+B    | Show / hide window |

### Search Field
| Shortcut    | Action                    |
|-------------|---------------------------|
| Tab         | Accept suggestion         |
| Alt+↓       | Next suggestion           |
| Alt+↑       | Previous suggestion       |
| Alt+←       | Move cursor back one word |
| Alt+→       | Move cursor forward one word |
| Ctrl+W      | Delete previous word      |
| Ctrl+U      | Delete to line start      |
| Ctrl+K      | Delete to line end        |
| Shift+Alt+K | Clear entire line         |

### Results List
| Shortcut | Action                                  |
|----------|-----------------------------------------|
| ↓ / ↑   | Navigate results                        |
| Enter    | Open selected URL in default browser    |
