Full-text search over the web pages and documents preserved by [Arquivo.pt](https://arquivo.pt), the Portuguese
web archive, from 1996 onwards.

Each result is an archived capture of a page or document (HTML, PDF, Office documents, ...): its title, a snippet of
the text matching the query, when it was captured, and links to replay it, see its screenshot, read its extracted text
and download the original file.

## Searching

Search with `GET /textsearch?q=<terms>`:

- Every term must match, so adding terms narrows the results.
- Terms within double quotes match as an exact phrase, e.g. `q="António Costa"`.
- A leading `-` excludes the pages containing a term, e.g. `q=Albert -Einstein`.
- `q` takes words, not addresses: a URL in `q` is rejected with `400 Bad Request`. To list the captures of a URL use
  `versionHistory`, or the [CDX server](https://arquivo.pt/cdxserverapi) and [Memento](https://arquivo.pt/memento)
  APIs.

The results can be narrowed down by capture date (`from`, `to`), site (`siteSearch`), document type (`type`),
collection (`collection`), exact title (`titleSearch`) and detected language (`language`, `minLanguageConfidence`).

## Results

- **Pagination**: `maxItems` results are returned per page (50 by default, at most 500), starting at `offset`.
  `next_page` and `previous_page` link to the neighbouring pages. `estimated_nr_results` is an approximate count of
  the matching pages.
- **Deduplication**: pages are grouped by `dedupField` (the title, or the URL when searching within a site) and only
  the newest `dedupValue` results of each group are returned. `dedupValue=-1` turns deduplication off.
- **Fields**: `fields` restricts each result to the listed fields. Listing `spellcheck` also asks for a spelling
  correction of the query, replied in `suggested_query` (empty when the query looks well spelled).
- **Timeline**: `timeline=true` adds the number of matching pages per year, and the share of each year's archived
  pages they make up.
- **Ranking**: `yearBalance` lifts the pages of the years the archive holds the least of, which would otherwise be
  outranked by the better covered years.

Responses are JSON (`prettyPrint=true` indents them) and can be requested cross-origin from a browser.
