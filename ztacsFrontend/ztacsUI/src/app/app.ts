import { Component, signal } from '@angular/core';
import { RouterOutlet } from '@angular/router';
import { Header } from './components/header/header';
import { DragDropModule } from '@angular/cdk/drag-drop';

@Component({

  selector: 'app-root',
  standalone: true,
  imports: [RouterOutlet, Header,DragDropModule],
  templateUrl: './app.html',
  styleUrls: ['./app.css']
})
export class AppComponent {
  protected readonly title = signal('ztacsUI');
}
