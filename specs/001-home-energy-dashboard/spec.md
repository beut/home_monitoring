# Feature Specification: Pulpit monitoringu domu (fotowoltaika, pompa ciepła, temperatury)

**Feature Branch**: `001-home-energy-dashboard`

**Created**: 2026-09-23

**Status**: Draft

**Input**: User description: "stworz aplikacje mobilna ktora bedzie rownoczesnie na ekranie pobierac dane z chmury apsystem (fotowoltaika), od panasonica (pompa ciepla) i tado (temperatura w pokojach)"

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Jeden ekran z bieżącym stanem całego domu (Priority: P1)

Właściciel domu otwiera aplikację na telefonie i na jednym ekranie, bez przełączania się między trzema aplikacjami producentów, widzi jednocześnie: ile prądu produkuje teraz instalacja fotowoltaiczna (APsystems), w jakim stanie pracuje pompa ciepła (Panasonic) oraz jaka jest temperatura w poszczególnych pokojach (tado). Dane z trzech źródeł ładują się równolegle i każda sekcja pokazuje się, gdy tylko jej dane są dostępne.

**Why this priority**: To jest istota aplikacji — szybki, zbiorczy podgląd domu. Bez tego aplikacja nie ma wartości.

**Independent Test**: Po skonfigurowaniu kont wszystkich trzech usług otwarcie aplikacji pokazuje na jednym ekranie trzy sekcje z aktualnymi danymi, zgodnymi z tym, co pokazują oficjalne aplikacje producentów.

**Acceptance Scenarios**:

1. **Given** skonfigurowane konta APsystems, Panasonic i tado, **When** użytkownik otwiera aplikację, **Then** na jednym ekranie widzi sekcję fotowoltaiki (bieżąca moc, energia wyprodukowana dziś), sekcję pompy ciepła (tryb pracy, włączona/wyłączona, temperatura zewnętrzna, temperatura wody grzewczej/ciepłej wody użytkowej) i sekcję pokoi (lista pokoi z temperaturą bieżącą, zadaną i wilgotnością).
2. **Given** jedna z usług odpowiada wolniej niż pozostałe, **When** aplikacja pobiera dane, **Then** sekcje usług, które już odpowiedziały, są wyświetlone od razu, a wolniejsza sekcja pokazuje wskaźnik ładowania.
3. **Given** aplikacja jest otwarta na ekranie głównym, **When** mija interwał odświeżania, **Then** dane wszystkich trzech sekcji odświeżają się automatycznie, a przy każdej sekcji widać czas ostatniej aktualizacji.
4. **Given** aplikacja jest otwarta, **When** użytkownik przeciąga ekran w dół, **Then** wszystkie trzy źródła są odświeżane natychmiast.

---

### User Story 2 - Konfiguracja kont trzech usług (Priority: P1)

Przy pierwszym uruchomieniu (i później w ustawieniach) użytkownik podaje dane logowania do każdej z trzech chmur: APsystems, Panasonic i tado. Aplikacja weryfikuje każde konto osobno i informuje, czy połączenie się udało. Dane logowania są zapamiętywane bezpiecznie na telefonie, więc nie trzeba ich podawać ponownie.

**Why this priority**: Bez podłączenia kont nie da się pobrać żadnych danych; jest to warunek konieczny dla User Story 1.

**Independent Test**: Użytkownik wprowadza dane jednego konta, aplikacja potwierdza połączenie; po zamknięciu i ponownym otwarciu aplikacji połączenie nadal działa bez ponownego logowania.

**Acceptance Scenarios**:

1. **Given** pierwsze uruchomienie aplikacji, **When** użytkownik otwiera aplikację, **Then** zostaje poprowadzony do ekranu konfiguracji kont z trzema osobnymi pozycjami (APsystems, Panasonic, tado).
2. **Given** ekran konfiguracji, **When** użytkownik poda poprawne dane dla usługi i zatwierdzi, **Then** aplikacja potwierdza połączenie i oznacza usługę jako podłączoną.
3. **Given** ekran konfiguracji, **When** użytkownik poda błędne dane, **Then** aplikacja pokazuje czytelny komunikat, której usługi dotyczy błąd, i nie zapisuje błędnych danych.
4. **Given** podłączone tylko dwie z trzech usług, **When** użytkownik przechodzi do ekranu głównego, **Then** widzi dane z dwóch usług, a sekcja trzeciej zawiera zachętę „Podłącz konto”.
5. **Given** podłączona usługa, **When** użytkownik wybierze „Odłącz”, **Then** dane logowania tej usługi są usuwane z telefonu.

---

### User Story 3 - Odporność na awarie pojedynczej usługi (Priority: P2)

Gdy jedna z chmur producentów jest niedostępna, sesja wygasła albo telefon traci połączenie z internetem, użytkownik nadal widzi dane z pozostałych usług, a przy problematycznej sekcji — ostatnio pobrane wartości oznaczone jako nieaktualne oraz zrozumiały komunikat.

**Why this priority**: Chmury producentów bywają niedostępne; aplikacja musi pozostać użyteczna, ale to rozszerzenie podstawowego scenariusza.

**Independent Test**: Symulacja niedostępności jednej usługi — pozostałe dwie sekcje działają normalnie, a sekcja niedostępnej usługi pokazuje ostatnie dane z oznaczeniem czasu i komunikatem błędu z możliwością ponowienia.

**Acceptance Scenarios**:

1. **Given** chmura tado nie odpowiada, **When** aplikacja odświeża dane, **Then** sekcje fotowoltaiki i pompy ciepła są aktualne, a sekcja pokoi pokazuje ostatnie znane wartości z informacją „Dane z godz. HH:MM — usługa niedostępna” i przyciskiem „Spróbuj ponownie”.
2. **Given** brak internetu w telefonie, **When** użytkownik otwiera aplikację, **Then** widzi ostatnio zapamiętane dane wszystkich usług z wyraźną informacją o braku połączenia.
3. **Given** wygasła sesja/uprawnienia jednej usługi, których nie da się odnowić automatycznie, **When** aplikacja próbuje pobrać dane, **Then** sekcja tej usługi prosi o ponowne zalogowanie, nie blokując pozostałych.

---

### Edge Cases

- Nocą (brak produkcji) sekcja fotowoltaiki pokazuje moc 0 i energię wyprodukowaną dziś — nie jest to traktowane jako błąd.
- Konto tado ma wiele domów lub konto Panasonic wiele urządzeń — aplikacja pokazuje pierwszy dom/urządzenie domyślnie i pozwala wybrać właściwe w ustawieniach.
- Pompa ciepła z dwiema strefami grzewczymi — obie strefy są pokazane osobno.
- Pokój tado bez podłączonego czujnika wilgotności — pole wilgotności jest pominięte, a nie pokazane jako 0.
- Usługa ogranicza liczbę zapytań (limit) — aplikacja nie odpytuje częściej niż pozwala usługa i wyświetla ostatnie dane zamiast błędu.
- Producent zmienia sposób logowania lub dostęp do danych — sekcja danej usługi pokazuje błąd połączenia, pozostałe działają dalej.
- Aplikacja w tle lub ekran wyłączony — odświeżanie jest wstrzymywane i wznawiane po powrocie.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: Aplikacja MUSI wyświetlać na jednym ekranie jednocześnie dane z trzech źródeł: instalacji fotowoltaicznej APsystems, pompy ciepła Panasonic i systemu tado.
- **FR-002**: Aplikacja MUSI pobierać dane z trzech źródeł równolegle i wyświetlać każdą sekcję niezależnie, gdy tylko jej dane są dostępne.
- **FR-003**: Sekcja fotowoltaiki MUSI pokazywać co najmniej: bieżącą moc produkcji oraz energię wyprodukowaną dzisiaj; POWINNA pokazywać także energię z bieżącego miesiąca i całkowitą.
- **FR-004**: Sekcja pompy ciepła MUSI pokazywać co najmniej: stan (włączona/wyłączona), tryb pracy (grzanie/chłodzenie/ciepła woda/auto), temperaturę zewnętrzną, temperaturę bieżącą i zadaną dla każdej strefy grzewczej oraz dla zasobnika ciepłej wody użytkowej (jeśli występuje).
- **FR-005**: Sekcja pokoi MUSI pokazywać listę pokoi (stref) tado z temperaturą bieżącą, temperaturą zadaną, informacją czy ogrzewanie jest aktywne oraz wilgotnością (jeśli dostępna).
- **FR-006**: Aplikacja MUSI automatycznie odświeżać dane, gdy ekran główny jest widoczny, z częstotliwością dostosowaną do limitów zapytań każdej usługi (dla APsystems limit 1000 zapytań/miesiąc — odświeżanie w ciągu dnia mniej więcej co 15–40 min, w nocy wcale), oraz umożliwiać ręczne odświeżenie gestem przeciągnięcia.
- **FR-007**: Każda sekcja MUSI pokazywać czas ostatniej udanej aktualizacji swoich danych.
- **FR-008**: Użytkownicy MUSZĄ móc podłączyć, sprawdzić i odłączyć konto każdej z trzech usług niezależnie od pozostałych.
- **FR-009**: Aplikacja MUSI weryfikować dane logowania przy podłączaniu konta i wyświetlać czytelny komunikat o błędzie wskazujący konkretną usługę.
- **FR-010**: Dane logowania i tokeny dostępu MUSZĄ być przechowywane wyłącznie na urządzeniu użytkownika, w zaszyfrowanym magazynie systemowym, i nie mogą być przesyłane nigdzie poza oficjalne serwery danego producenta.
- **FR-011**: Aplikacja MUSI zapamiętywać ostatnio pobrane dane każdej usługi i wyświetlać je (oznaczone jako nieaktualne, z czasem pobrania), gdy bieżące pobranie się nie powiedzie lub brak internetu.
- **FR-012**: Błąd lub niedostępność jednej usługi NIE MOŻE blokować wyświetlania danych z pozostałych usług.
- **FR-013**: Aplikacja MUSI automatycznie odnawiać sesję usługi, gdy jest to możliwe, i prosić o ponowne logowanie tylko wtedy, gdy automatyczne odnowienie się nie powiedzie.
- **FR-014**: Aplikacja MUSI wstrzymywać odświeżanie, gdy nie jest widoczna na ekranie, liczyć wykorzystane zapytania i nie przekraczać ustawionego miesięcznego budżetu zapytań danej usługi (także przy wielokrotnym ręcznym odświeżaniu — wtedy informuje, dlaczego dane nie zostały pobrane).
- **FR-015**: Gdy konto zawiera wiele domów/instalacji/urządzeń, użytkownik MUSI móc wybrać w ustawieniach, które mają być wyświetlane.
- **FR-016**: Interfejs aplikacji MUSI być w języku polskim, a wartości wyświetlane w jednostkach metrycznych (°C, W/kW, kWh, %).
- **FR-017**: Aplikacja służy wyłącznie do odczytu — w tej wersji nie zmienia ustawień urządzeń (np. temperatury zadanej).
- **FR-018**: Aplikacja pokazuje wyłącznie bieżące wartości oraz sumy (dzienne, miesięczne, całkowite); wykresy i przeglądanie danych historycznych nie są częścią tej wersji.

### Key Entities *(include if feature involves data)*

- **Konto usługi**: podłączenie do jednej z trzech chmur (APsystems, Panasonic, tado); stan (niepodłączone / podłączone / wymaga ponownego logowania), wybrana instalacja/urządzenie/dom.
- **Odczyt fotowoltaiki**: bieżąca moc, energia dziś, energia w miesiącu, energia całkowita, czas odczytu.
- **Odczyt pompy ciepła**: stan pracy, tryb, temperatura zewnętrzna, lista stref (nazwa, temperatura bieżąca i zadana), zasobnik c.w.u. (temperatura bieżąca i zadana), czas odczytu.
- **Pokój (strefa tado)**: nazwa, temperatura bieżąca, temperatura zadana, wilgotność, czy grzeje, czas odczytu.
- **Migawka danych**: ostatni udany odczyt danej usługi zapamiętany na urządzeniu wraz z czasem pobrania, używany przy błędach i braku sieci.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: Przy działającym internecie i dostępnych usługach użytkownik widzi dane ze wszystkich trzech usług w czasie poniżej 5 sekund od otwarcia aplikacji.
- **SC-002**: Ostatnio zapamiętane dane pojawiają się na ekranie w czasie poniżej 1 sekundy od otwarcia aplikacji, jeszcze przed zakończeniem pobierania nowych.
- **SC-003**: Wartości pokazywane w aplikacji są zgodne z wartościami w oficjalnych aplikacjach producentów (z dokładnością do opóźnienia odświeżania samych chmur).
- **SC-004**: Podłączenie wszystkich trzech kont przy pierwszym uruchomieniu zajmuje użytkownikowi mniej niż 5 minut.
- **SC-005**: W przypadku niedostępności jednej usługi pozostałe dwie sekcje wyświetlają aktualne dane w 100% przypadków.
- **SC-006**: Użytkownik nie musi ponownie logować się do żadnej usługi częściej niż wymaga tego sam producent (docelowo: nie częściej niż raz na 30 dni).

## Assumptions

- Realizacja etapami: etap 1 obejmuje wyłącznie fotowoltaikę (APsystems); pompa ciepła (Panasonic) i pokoje (tado) zostaną dodane w kolejnych etapach.
- APsystems udostępnia 1000 zapytań miesięcznie na konto dostępowe, współdzielone z innymi narzędziami użytkownika; aplikacja domyślnie zużywa najwyżej 800.

- Aplikacja jest przeznaczona dla jednego gospodarstwa domowego i jednego użytkownika (właściciela kont); brak kont użytkowników w samej aplikacji.
- Pierwsza wersja powstaje na telefony z Androidem (zgodnie z konfiguracją repozytorium); iOS jest poza zakresem v1.
- Aplikacja łączy się bezpośrednio z chmurami producentów z telefonu — bez własnego serwera pośredniczącego.
- „Panasonic (pompa ciepła)” oznacza pompę ciepła Aquarea obsługiwaną przez chmurę Panasonic Aquarea Smart Cloud.
- Użytkownik posiada aktywne konta we wszystkich trzech usługach oraz, jeśli producent tego wymaga (np. APsystems), uzyska dane dostępowe do interfejsu danych producenta.
- Dostęp do danych Panasonic może opierać się na nieoficjalnym interfejsie; zmiany po stronie producenta mogą czasowo przerwać działanie tej sekcji (co pokrywa FR-012).
- Sterowanie urządzeniami, wykresy/dane historyczne, powiadomienia push i widżety na ekranie głównym telefonu są poza zakresem v1.
- Domyślny interwał odświeżania (5 minut) wynika z częstotliwości aktualizacji danych w chmurach producentów i ich limitów zapytań.
