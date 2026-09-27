$ErrorActionPreference = 'Stop'
# Explicit, editable discovery list. Endpoints are checked by the normal collector;
# unavailable feeds remain visible in its health report, never counted as healthy.
$registryPath = Join-Path $PSScriptRoot '../work/news/sources.json'
$bundledPath = Join-Path $PSScriptRoot '../src/main/resources/news-sources.json'
$basePath = if (Test-Path -LiteralPath $registryPath) { $registryPath } else { $bundledPath }
$sources = [Collections.Generic.List[object]]::new()
foreach ($source in @(Get-Content -LiteralPath $basePath -Raw | ConvertFrom-Json)) { $sources.Add($source) }
function Add-Feed($id, $publisher, $name, $category, $url, $symbol = '') {
    if ($sources.id -contains $id -or $sources.url -contains $url) { return }
    $sources.Add([pscustomobject]@{id=$id;publisher=$publisher;name=$name;category=$category;url=$url;enabled=$true;symbol=$symbol})
}
$direct = @'
bloomberg-markets|Bloomberg|Markets|Business|https://feeds.bloomberg.com/markets/news.rss
bloomberg-economics|Bloomberg|Economics|Business|https://feeds.bloomberg.com/economics/news.rss
bloomberg-tech|Bloomberg|Technology|Technology|https://feeds.bloomberg.com/technology/news.rss
ft-world|Financial Times|World|World|https://www.ft.com/world?format=rss
ft-markets|Financial Times|Markets|Business|https://www.ft.com/markets?format=rss
ft-companies|Financial Times|Companies|Business|https://www.ft.com/companies?format=rss
economist|The Economist|Latest|World|https://www.economist.com/latest/rss.xml
economictimes|The Economic Times|Markets|Business|https://economictimes.indiatimes.com/markets/rssfeeds/1977021501.cms
mint|Mint|Markets|Business|https://www.livemint.com/rss/markets
businessstandard|Business Standard|Markets|Business|https://www.business-standard.com/rss/markets-106.rss
moneycontrol|Moneycontrol|Business|Business|https://www.moneycontrol.com/rss/business.xml
financialexpress|Financial Express|Business|Business|https://www.financialexpress.com/feed/
investing-news|Investing.com|News|Business|https://www.investing.com/rss/news.rss
investing-stocks|Investing.com|Stocks|Business|https://www.investing.com/rss/news_25.rss
investing-economy|Investing.com|Economy|Business|https://www.investing.com/rss/news_14.rss
seekingalpha|Seeking Alpha|Market news|Business|https://seekingalpha.com/market_currents.xml
benzinga|Benzinga|Markets|Business|https://www.benzinga.com/feed
zacks|Zacks|Commentary|Business|https://www.zacks.com/feeds/commentary.xml
investopedia|Investopedia|News|Business|https://www.investopedia.com/feedbuilder/feed/getfeed/?feedName=rss_headline
barrons|Barron's|Markets|Business|https://feeds.content.dowjones.io/public/rss/barrons
prnewswire|PR Newswire|Company releases|Press releases|https://www.prnewswire.com/rss/news-releases-list.rss
globenewswire|GlobeNewswire|Company releases|Press releases|https://www.globenewswire.com/RssFeed/subjectcode/1-Corporate%20News/feedTitle/GlobeNewswire%20-%20Corporate%20News
businesswire|Business Wire|Company releases|Press releases|https://feed.businesswire.com/rss/home/?rss=G1QFDERJXkJeEFpRWg==
accesswire|ACCESS Newswire|Company releases|Press releases|https://www.accesswire.com/rss.aspx
stocktitan|Stock Titan|Company releases|Press releases|https://www.stocktitan.net/rss
sec-press|US Securities and Exchange Commission|Press releases|Official releases|https://www.sec.gov/news/pressreleases.rss
fdic|Federal Deposit Insurance Corporation|Press releases|Official releases|https://www.fdic.gov/news/press-releases/rss.xml
imf|International Monetary Fund|News|Official releases|https://www.imf.org/en/News/RSS
bankengland|Bank of England|News|Official releases|https://www.bankofengland.co.uk/rss/news
rba|Reserve Bank of Australia|Media releases|Official releases|https://www.rba.gov.au/rss/rss-cb-media-releases.xml
bankcanada|Bank of Canada|Press releases|Official releases|https://www.bankofcanada.ca/content_type/press-releases/feed/
bis|Bank for International Settlements|Press releases|Official releases|https://www.bis.org/doclist/all_pressrels.rss
fda|US Food and Drug Administration|Press announcements|Official releases|https://www.fda.gov/about-fda/contact-fda/stay-informed/rss-feeds/press-releases/rss.xml
fiercebiotech|Fierce Biotech|Biotechnology|Healthcare|https://www.fiercebiotech.com/rss/xml
fiercepharma|Fierce Pharma|Pharmaceuticals|Healthcare|https://www.fiercepharma.com/rss/xml
statnews|STAT|Health and medicine|Healthcare|https://www.statnews.com/feed/
medicalnewstoday|Medical News Today|Health|Healthcare|https://www.medicalnewstoday.com/newsfeeds/rss/medical_news_today.xml
sciencedaily|ScienceDaily|Research|Science|https://www.sciencedaily.com/rss/all.xml
nature|Nature|Research|Science|https://www.nature.com/nature.rss
physorg|Phys.org|Science|Science|https://phys.org/rss-feed/
nasa|NASA|News|Science|https://www.nasa.gov/feed/
wired|WIRED|Technology|Technology|https://www.wired.com/feed/rss
venturebeat|VentureBeat|Technology|Technology|https://venturebeat.com/feed/
theregister|The Register|Technology|Technology|https://www.theregister.com/headlines.atom
techradar|TechRadar|Technology|Technology|https://www.techradar.com/rss
zdnet|ZDNET|Technology|Technology|https://www.zdnet.com/news/rss.xml
bleepingcomputer|BleepingComputer|Cybersecurity|Technology|https://www.bleepingcomputer.com/feed/
securityweek|SecurityWeek|Cybersecurity|Technology|https://www.securityweek.com/feed/
tomshardware|Tom's Hardware|Semiconductors and hardware|Technology|https://www.tomshardware.com/feeds/all
semiengineering|Semiconductor Engineering|Semiconductors|Technology|https://semiengineering.com/feed/
eetimes|EE Times|Electronics|Technology|https://www.eetimes.com/feed/
utilitydive|Utility Dive|Utilities|Business|https://www.utilitydive.com/feeds/news/
retaildive|Retail Dive|Retail|Business|https://www.retaildive.com/feeds/news/
bankingdive|Banking Dive|Banking|Business|https://www.bankingdive.com/feeds/news/
manufacturingdive|Manufacturing Dive|Manufacturing|Business|https://www.manufacturingdive.com/feeds/news/
supplychaindive|Supply Chain Dive|Supply chains|Business|https://www.supplychaindive.com/feeds/news/
freightwaves|FreightWaves|Freight and logistics|Business|https://www.freightwaves.com/news/feed
maritimeexecutive|The Maritime Executive|Shipping|Business|https://maritime-executive.com/rss
mining|MINING.COM|Mining|Business|https://www.mining.com/feed/
renewablesnow|Renewables Now|Energy|Business|https://renewablesnow.com/news/rss/
coindesk|CoinDesk|Digital assets|Business|https://www.coindesk.com/arc/outboundfeeds/rss/
cointelegraph|Cointelegraph|Digital assets|Business|https://cointelegraph.com/rss
decrypt|Decrypt|Digital assets|Business|https://decrypt.co/feed
dw-business|Deutsche Welle|Business|Business|https://rss.dw.com/rdf/rss-en-bus
euronews|Euronews|World|World|https://www.euronews.com/rss?level=theme&name=news
swissinfo|SWI swissinfo.ch|Switzerland|World|https://www.swissinfo.ch/eng/rss
thelocal|The Local Europe|Europe|World|https://www.thelocal.com/feeds/rss.php
ansa|ANSA|Italy and world|World|https://www.ansa.it/english/news/english_rss.xml
spiegel|Der Spiegel|International|World|https://www.spiegel.de/international/index.rss
deutschewelle-german|Deutsche Welle|German news|World|https://rss.dw.com/rdf/rss-de-all
elpais|El Pais|International Spanish|World|https://feeds.elpais.com/mrss-s/pages/ep/site/elpais.com/portada
lemonde-fr|Le Monde|French headlines|World|https://www.lemonde.fr/rss/une.xml
abc-australia-business|ABC Australia|Business|Business|https://www.abc.net.au/news/feed/51892/rss.xml
rnz|Radio New Zealand|International|World|https://www.rnz.co.nz/rss/world.xml
straitstimes|The Straits Times|Business|Business|https://www.straitstimes.com/news/business/rss.xml
bangkokpost|Bangkok Post|Business|Business|https://www.bangkokpost.com/rss/data/business.xml
nhk|NHK|Japan English|World|https://www3.nhk.or.jp/rss/news/cat0.xml
koreaherald|The Korea Herald|Headlines|World|https://www.koreaherald.com/rss/020000000000.xml
koreatimes|The Korea Times|Business|Business|https://www.koreatimes.co.kr/www/rss/biz.xml
almonitor|Al-Monitor|Middle East|World|https://www.al-monitor.com/rss
middleeasteye|Middle East Eye|Middle East|World|https://www.middleeasteye.net/rss
arabnews|Arab News|World|World|https://www.arabnews.com/cat/241/rss.xml
daily-maverick|Daily Maverick|South Africa|World|https://www.dailymaverick.co.za/dmrss/
moneyweb|Moneyweb|African business|Business|https://www.moneyweb.co.za/feed/
businessdailyafrica|Business Daily Africa|Business|Business|https://www.businessdailyafrica.com/bd/rss
premiumtimes|Premium Times|Nigeria|World|https://www.premiumtimesng.com/feed
theconversation|The Conversation|Research and analysis|World|https://theconversation.com/global/articles.atom
restofworld|Rest of World|Global technology|Technology|https://restofworld.org/feed/latest/
nikkei|Nikkei Asia|Asia|World|https://asia.nikkei.com/rss/feed/nar
guardian-economics|The Guardian|Economics|Business|https://www.theguardian.com/business/economics/rss
pbs-economy|PBS News|Economy|Business|https://www.pbs.org/newshour/feeds/rss/economy
npr-business|NPR|Business|Business|https://feeds.npr.org/1006/rss.xml
'@
foreach ($row in $direct -split '\r?\n') { if ($row) { $fields=$row.Split('|'); Add-Feed @fields } }

# Company names reduce ambiguous one-letter ticker searches. Discovery remains
# provider-selected and is labelled separately from direct publisher feeds.
$companies = @'
AAPL|Apple Inc
MSFT|Microsoft
NVDA|Nvidia
AMZN|Amazon
GOOGL|Alphabet Google
META|Meta Platforms
TSLA|Tesla
AMD|Advanced Micro Devices
AVGO|Broadcom
NFLX|Netflix
INTC|Intel
MU|Micron Technology
PLTR|Palantir
UBER|Uber
JPM|JPMorgan Chase
BAC|Bank of America
XOM|Exxon Mobil
CVX|Chevron
WMT|Walmart
DIS|Walt Disney
ORCL|Oracle
CRM|Salesforce
ADBE|Adobe
CSCO|Cisco
QCOM|Qualcomm
TXN|Texas Instruments
AMAT|Applied Materials
LRCX|Lam Research
IBM|IBM
V|Visa Inc
MA|Mastercard
WFC|Wells Fargo
C|Citigroup
GS|Goldman Sachs
MS|Morgan Stanley
JNJ|Johnson & Johnson
UNH|UnitedHealth
LLY|Eli Lilly
PFE|Pfizer
ABBV|AbbVie
MRK|Merck
KO|Coca-Cola
PEP|PepsiCo
COST|Costco
PG|Procter & Gamble
CAT|Caterpillar
GE|GE Aerospace
BA|Boeing
COP|ConocoPhillips
NEE|NextEra Energy
SPY|SPDR S&P 500 ETF
QQQ|Invesco QQQ
IWM|iShares Russell 2000 ETF
DIA|SPDR Dow Jones Industrial Average ETF
VTI|Vanguard Total Stock Market ETF
XLK|Technology Select Sector SPDR
XLF|Financial Select Sector SPDR
XLE|Energy Select Sector SPDR
XLV|Health Care Select Sector SPDR
XLI|Industrial Select Sector SPDR
XLP|Consumer Staples Select Sector SPDR
XLY|Consumer Discretionary Select Sector SPDR
XLU|Utilities Select Sector SPDR
XLB|Materials Select Sector SPDR
XLRE|Real Estate Select Sector SPDR
XLC|Communication Services Select Sector SPDR
SMH|VanEck Semiconductor ETF
SOXX|iShares Semiconductor ETF
XBI|SPDR S&P Biotech ETF
KRE|SPDR S&P Regional Banking ETF
EEM|iShares MSCI Emerging Markets ETF
EFA|iShares MSCI EAFE ETF
GLD|SPDR Gold Shares
SLV|iShares Silver Trust
TLT|iShares 20+ Year Treasury Bond ETF
HYG|iShares iBoxx High Yield Corporate Bond ETF
UVXY|ProShares Ultra VIX Short-Term Futures ETF
'@
foreach ($row in $companies -split '\r?\n') {
    if (!$row) { continue }
    $symbol,$company = $row.Split('|')
    $query = [Uri]::EscapeDataString('"'+$company+'" when:7d')
    Add-Feed ('google-stock-'+$symbol.ToLowerInvariant()) 'Google News discovery' ($symbol+' / '+$company) 'Stocks & ETFs' ('https://news.google.com/rss/search?q='+$query+'&hl=en-US&gl=US&ceid=US:en') $symbol
}
$topics=@('earnings revenue guidance','mergers acquisitions','dividends buybacks','IPO stock offering','bankruptcy restructuring','semiconductors','artificial intelligence data centers','cloud software cybersecurity','pharmaceutical clinical trial FDA','banks credit lending','insurance underwriting','real estate REIT housing','retail consumer spending','automotive electric vehicles','aerospace defense','airlines travel hotels','shipping logistics freight','oil gas energy','renewable energy utilities','mining metals commodities','agriculture food fertilizer','telecommunications','interest rates inflation employment','tariffs trade sanctions','antitrust competition regulation')
for($i=0; $i -lt $topics.Count; $i++) {
    $query=[Uri]::EscapeDataString('('+ $topics[$i] +') when:7d')
    Add-Feed ('google-sector-'+($i+1)) 'Google News discovery' $topics[$i] 'Business' ('https://news.google.com/rss/search?q='+$query+'&hl=en-US&gl=US&ceid=US:en')
}
$locales=@('CA|en-CA|en','SG|en-SG|en','NZ|en-NZ|en','ZA|en-ZA|en','NG|en-NG|en','KE|en-KE|en','PH|en-PH|en','MY|en-MY|en','IE|en-IE|en','FR|fr|fr','DE|de|de','IT|it|it','ES|es|es','BR|pt-BR|pt-419','MX|es-419|es-419','JP|ja|ja','KR|ko|ko','TW|zh-TW|zh-Hant','HK|zh-HK|zh-Hant','ID|id|id','TH|th|th','TR|tr|tr','EG|ar|ar','SA|ar|ar','IL|he|he','AR|es-419|es-419','CL|es-419|es-419','CO|es-419|es-419')
foreach($locale in $locales) {
    $region,$language,$edition=$locale.Split('|')
    Add-Feed ('google-region-'+$region.ToLowerInvariant()) 'Google News discovery' ($region+' business headlines') 'Business' ('https://news.google.com/rss/headlines/section/topic/BUSINESS?hl='+$language+'&gl='+$region+'&ceid='+$region+':'+$edition)
}
if ($sources.Count -gt 500) { throw 'Source registry exceeds 500 feeds' }
$json=ConvertTo-Json -InputObject @($sources.ToArray()) -Depth 6
$temporary=$registryPath+'.new'
[IO.File]::WriteAllText($temporary,$json,[Text.UTF8Encoding]::new($false))
Move-Item -LiteralPath $temporary -Destination $registryPath -Force
[IO.File]::WriteAllText($bundledPath,$json,[Text.UTF8Encoding]::new($false))
[pscustomobject]@{feeds=$sources.Count;configuredPublisherLabels=@($sources.publisher | Sort-Object -Unique).Count;stockFeeds=@($sources | Where-Object symbol).Count} | ConvertTo-Json
