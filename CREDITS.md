# Third-party attributions

Tt9 ships some data sourced from external, freely-licensed projects. Attributions are collected here.

## English bigram seed corpus

`app/src/main/assets/seed/en_bigrams.tsv.gz` is derived from the
[Google Books Ngram Corpus, 2020 release](https://storage.googleapis.com/books/ngrams/books/datasetsv3.html),
as processed and published by the [orgtre/google-books-ngram-frequency](https://github.com/orgtre/google-books-ngram-frequency)
project.

- License: **Creative Commons Attribution 3.0 Unported (CC-BY 3.0)** — see
  <https://creativecommons.org/licenses/by/3.0/>.
- Original source files: `2grams_english.csv`, `2grams_english-fiction.csv` from the
  `ngrams/` directory of the orgtre repository.
- Our processing: dedupe + T9-encoding + gzip via `app/bundle-bigram-seed.gradle`.

The corpus is used only to seed the QWERTY on-screen keyboard's next-word predictions at
install time so users see useful suggestions from day one. Once the user has typed for a
while their own MindReader-learned bigrams take over.
