# Guide technique — HCAdvancementsRedirect

## Point d’entrée

Cible : Paper 26.3 (`26.3.build.+`), Java 25 sans preview. Compilation avec `-Xlint:all` ;
examiner les warnings avant de les attribuer au plugin ou à une dépendance.

Ce dépôt appartient à la suite privée d’usage HeavenCube, publiée comme source consultable.
Il dépend obligatoirement de HCCore. Lire d’abord [AGENTS.md](../AGENTS.md), puis le Core voisin.
Le [guide commun](https://github.com/HeavenCube/HCPlugins-Core/blob/main/docs/ECOSYSTEM.md) décrit les règles Java/Paper, les contrats Core,
le packaging et la CI. Ce guide local décrit les particularités à préserver ; le code reste l’autorité.

## Dépendances et compilation

HCCore et PacketEvents obligatoires. Le tab client utilise le modèle d’item Nexo `nexo:vide` ; contrôler sa présence dans le pack serveur.

Cloner Core à côté ; JAR standard. PacketEvents reste compileOnly et plugin serveur, aucune copie ou relocation embarquée.

```powershell
.\gradlew.bat build
```

Utiliser JDK 25. Sous Linux : `./gradlew build`. Le JAR est dans `build/libs/` ; installer aussi
les plugins serveur requis. Un clone Core modifié affecte le classpath local ; noter son commit.
Après extension d’API commune, construire Core séparément et installer sa version compatible en premier.

## Commandes et permissions

`/hcplugins advancementsredirect reload` : opérateurs. Une configuration rechargée avec commande absente/vide est distincte d’un échec de lecture : conserver l’avertissement métier.

La branche canonique est `/hcplugins advancementsredirect` ; elle est enregistrée chez Core, pas comme
une deuxième racine. Les résultats de reload, refus opérateur et autres textes partagés utilisent
`HCPluginsCore.translations(plugin)`. `{duration}` inclut déjà l’unité `ms`.

## Fichiers et données

`plugins/HCPlugins/HCAdvancementsRedirect.yml` : clé `console-command` avec remplacement littéral `{player}`. Aucun fichier de persistance propre.

Valeurs par défaut dans `src/main/resources/`, jamais écrasées à chaque démarrage. Aucun import
automatique des anciens dossiers du monorepo. Messages communs dans `plugins/HCPlugins/translations.yml` ;
messages métier locaux. Modifier le fichier partagé se recharge avec `/hcplugins core reload`.

## Chemin d’exécution

AdvancementInjector ajoute le tab de redirection côté client et prévoit un fallback de connexion. AdvancementClickListener filtre le paquet ADVANCEMENT_TAB pour `heavencube:menu_redirect`, l’annule, ferme l’écran client, coalesce les demandes par joueur et planifie ConfiguredCommandExecutor sur le thread serveur.

## Carte du code pour une modification

| Fichier | Responsabilité et points à préserver |
| --- | --- |
| [HCAdvancementsRedirect.java](../src/main/java/fr/noltox/hcplugins/advancementsbuttonredirect/HCAdvancementsRedirect.java) | Chargement configuration, listeners PacketEvents et commande Core. |
| [AdvancementInjector.java](../src/main/java/fr/noltox/hcplugins/advancementsbuttonredirect/AdvancementInjector.java) | Tab client, fallback différé et suppression au teardown. |
| [AdvancementClickListener.java](../src/main/java/fr/noltox/hcplugins/advancementsbuttonredirect/AdvancementClickListener.java) | Filtre exact du paquet, fermeture et anti-spam par joueur. |
| [ConfiguredCommandExecutor.java](../src/main/java/fr/noltox/hcplugins/advancementsbuttonredirect/ConfiguredCommandExecutor.java) | Commande console, contexte joueur et remplacement {player}. |
| [AdvancementsCommand.java](../src/main/java/fr/noltox/hcplugins/advancementsbuttonredirect/command/AdvancementsCommand.java) | Reload et avertissement pour console-command vide. |

`src/main/resources/paper-plugin.yml` définit identité, dépendances et permissions serveur.
`settings.gradle.kts` définit les builds composites ; `build.gradle.kts` le packaging.
`.github/workflows/build.yml` appelle les actions partagées à `@main` ; `.github/dependabot.yml`
maintient les dépendances. Une mise à jour de dépendance doit conserver ces contrats.

## Invariants et zones à risque

- Plugin serveur `HCAdvancementsRedirect`, HCCore obligatoire ; module `advancementsredirect`.
- HCCore et PacketEvents obligatoires. Le tab client utilise le modèle d’item Nexo `nexo:vide` ; contrôler sa présence dans le pack serveur.
- Préserver tab uniquement client et identifiant `heavencube:menu_redirect` ; ne pas ajouter un vrai progrès serveur.
- Ne jamais exécuter la commande console sur le thread réseau ; revalider joueur/plugin avant exécution.
- Conserver coalescence du spam, traitement de reconnexion et nettoyage des tâches/listeners/pending commands.
- Ne pas renommer console-command ni {player} sans migration explicitement demandée.
- Ne pas remplacer PacketEvents par NMS/réflexion ni fermer des écrans sans filtrer exactement le tab concerné.

Avant une nouvelle logique transversale : chercher les usages dans Core et les autres plugins ;
ajouter au Core le contrat partagé réellement nécessaire avant le raccordement local. Ne pas recopier
un loader YAML, un registre de commandes ou un catalogue de traductions. Garder les événements et
états spécifiques ici. Thread serveur pour le jeu ; considérer callbacks et APIs tierces selon leur
thread réel, puis revalider le contexte avant mutation.

## Validation et limites

`ConfiguredCommandExecutorTest` couvre commande absente/vide, arguments et rejet des types YAML non textuels.
L'injection mémorise la session PacketEvents ; un ancien envoi ne dispense pas la nouvelle session du fallback.
Au disable isolé du plugin, retirer uniquement `heavencube:menu_redirect` des joueurs connectés
dont la session PacketEvents est encore courante. Lors d'un arrêt global, détecté par
[Server#isStopping()](https://jd.papermc.io/paper/26.3/org/bukkit/Server.html#isStopping()),
ne pas envoyer de paquet : les handlers Netty peuvent déjà être retirés avant le disable de PacketEvents.
Les tâches de fallback et les références de sessions sont nettoyées dans les deux cas.
`AdvancementInjectorTest` couvre ces scénarios avec un transport simulé, ainsi que les sessions remplacées,
les joueurs déconnectés et la libération des références malgré un échec d'envoi.
En jeu : écran via menu Échap et touche L, tab présent, clic normal, spam, reconnect, reload valide/vide/invalide, disable et absence d’interférence avec les autres progrès.

La compilation ne valide ni le protocole client, ni les conflits d’un autre plugin de progrès, ni le modèle Nexo.

Documentation seule : vérifier les liens locaux et le diff. Modification runtime : build, tests ciblés,
et scénario serveur correspondant. Rapporter seulement ce qui a été exécuté, avec résultat et limite.
Pour transfert entre IA, donner le commit Core testé et les fichiers/changements encore non committés.
