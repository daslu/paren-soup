[![Clojars Project](https://img.shields.io/clojars/v/paren-soup.svg)](https://clojars.org/paren-soup)

## Introduction

A library providing a ClojureScript viewer and editor that you can embed in any website. No, that was not a complete sentence. And neither is this. Be a rebel, like me, by using paren-soup. CodeMirror is nice if you want to support other languages, but why would you?

### [Try the demo!](http://oakes.github.io/paren-soup)

Here are the features:

* [Parinfer](http://shaunlebron.github.io/parinfer/)
* Syntax highlighting
* Rainbow delimiters
* Inline reader errors
* InstaREPL (à la Light Table)
* [Console mode](http://oakes.github.io/paren-soup/repl.html)

To use paren-soup in your own website, just go to [the releases section](https://github.com/oakes/paren-soup/releases) and download the latest files. In your HTML, link to one of the CSS files and use the following markup:
```html
<div class="paren-soup">
  <div class="instarepl"></div>
  <div class="numbers"></div>
  <div class="content" contenteditable="true">(+ 1 1) ; put initial code here</div>
</div>
<script type="text/javascript" src="paren-soup.js"></script>
<script type="text/javascript">
  paren_soup.core.init_all();
</script>
```
If you just want a viewer, not an editor, leave out the `contenteditable` attribute. If you don’t want the instaREPL or line numbers, remove the relevant divs and they will not appear. To get the code out of the content element via JavaScript or ClojureScript, read its `textContent` property. There is no API to learn!

## Build Instructions

I use [Boot](http://boot-clj.com/). To build the editor and run a server on http://localhost:3000, run `boot run`. To build a release version, run `boot build`.

A prebuilt copy of the compiler is already in the `resources` directory. If you want to build it yourself, go in the `compiler` directory and run `boot build`.

## Licensing

All files that originate from this project are dedicated to the public domain. I would love pull requests, and will assume that they are also dedicated to the public domain.
