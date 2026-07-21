# Downcard Website

Marketing site for Downcard, built with [Astro](https://astro.build).

## Commands

| Command           | Action                                      |
| ----------------- | ------------------------------------------- |
| `npm install`     | Install dependencies                        |
| `npm run dev`     | Start the dev server at `localhost:4321`    |
| `npm run build`   | Build the production site to `./dist/`      |
| `npm run preview` | Preview the production build locally        |

## Structure

- `src/pages/` — one file per route: `index` (home), `terms`, `privacy`, `contact`, `thanks`
- `src/layouts/Layout.astro` — shared shell (fonts, theme, nav, footer, meta tags)
- `src/components/` — `Nav` and `Footer`
- `src/styles/global.css` — theme variables and responsive rules
- `public/uploads/` — images (app icon, hero phone, screenshots)

The site is dark-themed by default. Theme variables for both light and dark live
in `global.css`; switch by changing `data-theme` on the `<html>` element in the layout.

## Contact form

The contact form posts to [FormSubmit](https://formsubmit.co). Submissions are
delivered to the address in the form `action` in
[`src/pages/contact.astro`](src/pages/contact.astro) (`hello@downcard.app`).

**One-time activation:** the first real submission from the production domain
triggers a confirmation email from FormSubmit to that address. Click the link in
it once to activate delivery. After a successful submit, users are redirected to
`/thanks` via the form's `_next` field — update that URL if the production domain
changes.
