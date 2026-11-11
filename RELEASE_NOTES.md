Versão de teste do card do download, de novo — agora com diagnóstico.

Se o card ainda resetar quando você troca de seção, esta versão tem um
registro que mostra exatamente o que aconteceu, pra eu não ficar
adivinhando. É só o log do app, nada aparece na tela.

O que também foi arrumado nesta:

- Quando o download volta a rodar depois de uma pausa ou de o sistema
  liberar memória, a barrinha não volta mais a ficar animada sem número.
  Ela continua mostrando a porcentagem real.
- O item que entra na fila não troca mais o que já estava na tela.
- Um aviso atrasado do download não embaralha mais o texto do card (ele
  mostrava "Baixando" junto com "99%").

Se ainda resetar, me manda o log que sai do comando:

adb logcat -s TuneGrabCard TuneGrabBus TuneGrab

Com ele eu vejo o instante exato em que o card mudou, e de onde veio a
mudança.
