function banner() {
    var speed = 10000;
    var modulo = 2;
    var index = 0;

    const banner1 = document.getElementById("banner-1");
    const banner2 = document.getElementById("banner-2");
    const banner3 = document.getElementById("banner-3");

    window.setInterval(function() {
        index = ++index % modulo;

        switch (index) {
          case 0:
              banner1.style.display = 'inline-block';
              banner2.style.display = 'none';
              banner3.style.display = 'none';
            break;
          case 1:
              banner1.style.display = 'none';
              banner2.style.display = 'inline-block';
              banner3.style.display = 'none';
            break;

          case 2:
              banner1.style.display = 'none';
              banner2.style.display = 'none';
              banner3.style.display = 'inline-block';
            break;
        }
    }, speed);
}
