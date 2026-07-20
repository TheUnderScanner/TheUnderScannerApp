# Serveur backend qui permet la communication avec l'app Android



# Connexion SSH au Jetson

Le Jetson rejoint le hotspot du téléphone, donc **son IP change à chaque session** — c'est
la même adresse que celle configurée dans les Réglages de l'app Android. Depuis un PC sur
le même réseau (auth par mot de passe, pas de clé installée pour l'instant) :

```
ssh orin4slam@<ip-du-jetson>      # ex. ssh orin4slam@10.75.93.211
```

**Attention** :  
Pour modifier le backend :
récupérer `app.py` du Jetson, patcher **cette** version, puis la renvoyer.

```
scp orin4slam@<ip>:~/underscanner-api/app.py ./app.py     # récupérer la version qui tourne
scp ./app.py orin4slam@<ip>:~/underscanner-api/app.py     # renvoyer après modif
```

## Commandes courantes une fois connecté

```
systemctl status underscanner            # le service tourne-t-il ?
sudo systemctl restart underscanner      # recharger après une modif de app.py
journalctl -u underscanner -f            # suivre les logs en direct
python3 -m py_compile app.py             # vérifier la syntaxe AVANT de redémarrer
cp app.py app.py.bak                     # filet de sécurité avant une grosse modif
ls /data/underscanner/{bags,maps,configs,notes,trajectories}   # les artefacts des scans
```

Test rapide de l'API sans passer par le téléphone (depuis le PC, en remplaçant `<ip>`) :

```
curl http://<ip>:8000/status
curl http://<ip>:8000/scans
```



# Après MAJ du fichier app.py
## Redémarrer le service pour qu'il recharge le fichier.
```
sudo systemctl restart underscanner
```

## Vérifie
```
journalctl -u underscanner -f
```

Tu dois revoir :
```
Application startup complete. 
Uvicorn running on http://0.0.0.0:8000 
```

`Ctrl+C` pour sortir du suivi des logs.






# Deux précisions utiles :
À distinguer de daemon-reload. Il y a deux commandes qui se ressemblent mais servent à des choses différentes :

`sudo systemctl restart underscanner` → tu as modifié le code (app.py). C'est ton cas courant. 
`sudo systemctl daemon-reload` → tu as modifié le fichier .service lui-même (le unit systemd). 
Rare — seulement si tu touches à /etc/systemd/system/underscanner.service.



Astuce pour le développement. Si tu es en train d'itérer beaucoup sur app.py (plusieurs modifs d'affilée), le cycle "édite → restart → teste" devient lourd. Dans ce cas, il est souvent plus pratique de couper temporairement le service et de lancer le backend à la main dans un terminal :

sudo systemctl stop underscanner

puis, dans un shell sourcé ROS :
cd ~/underscanner-api && source venv/bin/activate && python3 app.py

Comme ça tu vois les logs en direct, tu Ctrl+C / relances instantanément, et pas besoin de journalctl. Quand tu as fini d'itérer, tu remets le service :
sudo systemctl start underscanner
